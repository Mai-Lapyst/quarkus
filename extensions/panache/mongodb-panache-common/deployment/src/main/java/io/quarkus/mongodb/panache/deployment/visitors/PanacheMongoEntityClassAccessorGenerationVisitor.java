package io.quarkus.mongodb.panache.deployment.visitors;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkus.gizmo.Gizmo;
import io.quarkus.mongodb.panache.deployment.ReferenceEntityField;
import io.quarkus.mongodb.panache.deployment.ReferencedEntityField;
import io.quarkus.panache.common.deployment.EntityField;
import io.quarkus.panache.common.deployment.EntityModel;
import io.quarkus.panache.common.deployment.visitors.PanacheEntityClassAccessorGenerationVisitor;

public class PanacheMongoEntityClassAccessorGenerationVisitor extends ClassVisitor {

    private static final String BSON_IGNORE_SIGNATURE = "Lorg/bson/codecs/pojo/annotations/BsonIgnore;";
    private static final String JSON_IGNORE_SIGNATURE = "Lcom/fasterxml/jackson/annotation/JsonIgnore;";

    protected final Type thisClass;
    private final Map<String, ? extends EntityField> fields;
    private final Set<String> userMethods = new HashSet<>();

    public PanacheMongoEntityClassAccessorGenerationVisitor(ClassVisitor outputClassVisitor,
            ClassInfo entityInfo, EntityModel entityModel) {
        super(Gizmo.ASM_API_VERSION,
                new PanacheEntityClassAccessorGenerationVisitor(outputClassVisitor, entityInfo, entityModel));

        String className = entityInfo.name().toString();
        thisClass = Type.getType("L" + className.replace('.', '/') + ";");
        fields = entityModel != null ? entityModel.fields : null;
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        EntityField entityField = fields == null ? null : fields.get(name);
        if (entityField == null) {
            return super.visitField(access, name, descriptor, signature, value);
        }

        if (entityField instanceof ReferencedEntityField) {
            FieldVisitor superVisitor = super.visitField(access, name, descriptor, signature, value);
            return new FieldVisitor(Gizmo.ASM_API_VERSION, superVisitor) {
                @Override
                public void visitEnd() {
                    super.visitAnnotation(BSON_IGNORE_SIGNATURE, true);
                    super.visitEnd();
                }
            };
        } else if (entityField instanceof ReferenceEntityField) {
            FieldVisitor superVisitor = super.visitField(access, name, descriptor, signature, value);
            return new FieldVisitor(Gizmo.ASM_API_VERSION, superVisitor) {
                @Override
                public void visitEnd() {
                    super.visitAnnotation(JSON_IGNORE_SIGNATURE, true);
                    super.visitEnd();
                }
            };
        } else {
            return super.visitField(access, name, descriptor, signature, value);
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature,
            String[] exceptions) {

        userMethods.add(methodName + "/" + descriptor);

        if (methodName.equals("<init>")) {
            MethodVisitor superVisitor = super.visitMethod(access, methodName, descriptor, signature, exceptions);
            return new MethodVisitor(Gizmo.ASM_API_VERSION, superVisitor) {
                @Override
                public void visitInsn(final int opcode) {
                    if (opcode == Opcodes.RETURN) {
                        super.visitIntInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, thisClass.getInternalName(),
                                "mongoValidateReferencedFields", "()V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }

        return super.visitMethod(access, methodName, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        generateAccessors();
        generateMongoValidateReferencedFieldsMethod();

        super.visitEnd();
    }

    private void visitInitializeField(MethodVisitor mv, String name, String descriptor, Type initializerType) {
        Label label = new Label();
        mv.visitIntInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), name, descriptor);
        mv.visitJumpInsn(Opcodes.IFNONNULL, label);
        {
            mv.visitIntInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.NEW, initializerType.getInternalName());
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, initializerType.getInternalName(), "<init>", "()V",
                    false);
            mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), name, descriptor);
        }
        mv.visitLabel(label);
    }

    private void visitValidateReferencedField(MethodVisitor mv, ReferencedEntityField field) {
        /*
         * 1 2 erg
         * - - nothing
         * - + setEntity
         * + - setId
         * + + check_ids
         * 
         * aload_1
         * ifnull a
         * 
         * aload_2
         * ifnull b
         * 
         * => check_ids
         * -> end
         * 
         * b: => setId
         * -> end
         * 
         * a: aload_2
         * ifnull end
         * 
         * => setEntity
         * 
         * end:
         * 
         */

        // WRAPPED TYPE:
        // if (this.type.size() > 0 && this.type_id.size() > 0) then
        //      make error; we DONT assume any of the lists to be right (for now)
        // else if (this.type.size() == 0) then
        //      make types from this.type_id
        // else if (this.type_id.size() == 0) then
        //      make type_id from this.type
        // end

        Label lab_a = new Label();
        Label lab_b = new Label();
        Label lab_end = new Label();
        Label lab_check_ids_ok = new Label();

        if (!field.isContainerWrapped) {
            // if (localEntity != null && localId != null)
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitJumpInsn(Opcodes.IFNULL, lab_a);

            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitJumpInsn(Opcodes.IFNULL, lab_b);
        } else {
            // if (localEntity.size() > 0 && localId.size() > 0)
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getType(field.descriptor).getInternalName(), "size", "()I", true);
            mv.visitJumpInsn(Opcodes.IFLE, lab_a);

            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getType(field.id_descriptor).getInternalName(), "size", "()I",
                    true);
            mv.visitJumpInsn(Opcodes.IFLE, lab_b);
        }

        {
            if (!field.isContainerWrapped) {
                // check_ids
                // if (localEntity.id != localId) => error
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(field.descriptor).getInternalName(), field.bsonIdFieldName,
                        field.id_descriptor);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getType(field.id_descriptor).getInternalName(), "equals",
                        "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(Opcodes.IFNE, lab_check_ids_ok);
            }

            // make error
            {
                Type exceptionType = Type.getType("Ljava/lang/RuntimeException;");
                mv.visitTypeInsn(Opcodes.NEW, exceptionType.getInternalName());
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn("Detected inconsitency for referenced field: '" + field.name + "."
                        + field.bsonIdFieldName + "' and '"
                        + field.id_name + "' dont have the same value" + (field.isContainerWrapped ? "s" : ""));
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exceptionType.getInternalName(), "<init>",
                        "(Ljava/lang/String;)V", false);
                mv.visitInsn(Opcodes.ATHROW);
            }

            if (!field.isContainerWrapped) {
                mv.visitLabel(lab_check_ids_ok);
            }
        }
        mv.visitJumpInsn(Opcodes.GOTO, lab_end);

        mv.visitLabel(lab_b);
        if (!field.isContainerWrapped) {
            // set id field
            // ex: this.type_id = localEntity.id
            mv.visitIntInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(field.descriptor).getInternalName(), field.bsonIdFieldName,
                    field.id_descriptor);
            mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), field.id_name, field.id_descriptor);
        } else {
            // fill id field
            // ex: for (e : localEntity) { this.type_id.push(e.id); }
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            // Type.getType(field.descriptor).getInternalName()
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                    "()Ljava/util/Iterator", true);
            mv.visitVarInsn(Opcodes.ASTORE, 3);

            Label lab_loop_check = new Label();
            Label lab_loop_body = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, lab_loop_check);

            mv.visitLabel(lab_loop_body);

            // iter.next().id
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(field.entity_descriptor).getInternalName());
            mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(field.entity_descriptor).getInternalName(),
                    field.bsonIdFieldName,
                    field.id_descriptor);
            mv.visitVarInsn(Opcodes.ASTORE, 4);

            // localId.push( iter.next().id )
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(Opcodes.POP);

            mv.visitLabel(lab_loop_check);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
            mv.visitJumpInsn(Opcodes.IFNE, lab_loop_body);
        }
        mv.visitJumpInsn(Opcodes.GOTO, lab_end);

        mv.visitLabel(lab_a);
        if (!field.isContainerWrapped) {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitJumpInsn(Opcodes.IFNULL, lab_end);
            {
                // set entity field
                // ex: this.type = TypeEntity.findById(localId);
                mv.visitIntInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                Type rType = Type.getType(field.descriptor);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        rType.getInternalName(), "findById",
                        "(Ljava/lang/Object;)Lio/quarkus/mongodb/panache/PanacheMongoEntityBase;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, rType.getInternalName());
                mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), field.name,
                        field.descriptor);
            }
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getType(field.id_descriptor).getInternalName(), "size", "()I",
                    true);
            mv.visitJumpInsn(Opcodes.IFLE, lab_end);
            {
                // set entitys
                // ex: for (id : localId) { this.type.add(TypeEntity.findById(id)); }
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                        "()Ljava/util/Iterator", true);
                mv.visitVarInsn(Opcodes.ASTORE, 3);

                Label lab_loop_check = new Label();
                Label lab_loop_body = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, lab_loop_check);

                mv.visitLabel(lab_loop_body);

                // TypeEntity.findById( iter.next() )
                {
                    // iter.next()
                    mv.visitVarInsn(Opcodes.ALOAD, 3);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
                    // TypeEntity.findById( iter.next() )
                    Type rType = Type.getType(field.entity_descriptor);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            rType.getInternalName(), "findById",
                            "(Ljava/lang/Object;)Lio/quarkus/mongodb/panache/PanacheMongoEntityBase;", false);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, rType.getInternalName());
                    mv.visitVarInsn(Opcodes.ASTORE, 4);
                }

                // localEntity.push( TypeEntity.findById( iter.next() ) )
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
                mv.visitInsn(Opcodes.POP);

                mv.visitLabel(lab_loop_check);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                mv.visitJumpInsn(Opcodes.IFNE, lab_loop_body);
            }
        }

        mv.visitLabel(lab_end);
    }

    private void generateMongoValidateReferencedFieldsMethod() {
        MethodVisitor mv = super.visitMethod(Modifier.PRIVATE, "mongoValidateReferencedFields", "()V", null,
                new String[] {});
        mv.visitCode();

        for (EntityField entityField : fields.values()) {
            if (entityField instanceof ReferencedEntityField) {
                ReferencedEntityField field = (ReferencedEntityField) entityField;

                // ensure container-wrapped referenced entitiys are initialized first
                if (field.isContainerWrapped) {
                    Type type = Type.getType(field.descriptor);
                    switch (type.getSort()) {
                        case Type.ARRAY: {
                            // array dosnt need to be initialized; we set it on every update of the length
                            break;
                        }
                        case Type.OBJECT: {
                            String initializerDescriptor = field.descriptor;
                            if (field.typeMappings.containsKey(initializerDescriptor)) {
                                initializerDescriptor = field.typeMappings.get(initializerDescriptor);
                            }
                            Type initializerType = Type.getType(initializerDescriptor);

                            this.visitInitializeField(mv, field.name, field.descriptor, initializerType);
                            this.visitInitializeField(mv, field.id_name, field.id_descriptor, initializerType);
                            break;
                        }
                    }
                }

                // localEntity = this.type
                mv.visitIntInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), field.name, field.descriptor);
                mv.visitVarInsn(Opcodes.ASTORE, 1);

                // localId = this.type_id
                mv.visitIntInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), field.id_name, field.id_descriptor);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

                this.visitValidateReferencedField(mv, field);

            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
    }

    private void generateAccessors() {
        if (fields == null)
            return;

        for (EntityField field : fields.values()) {
            if (field instanceof ReferencedEntityField) {
                // referenced (entity) field! we take it.
                ReferencedEntityField referencedField = (ReferencedEntityField) field;

                // if the id_field dosnt exists, create it
                if (!referencedField.id_field_exists) {
                    FieldVisitor fv = this.visitField(Modifier.PROTECTED, referencedField.id_name,
                            referencedField.id_descriptor,
                            null, null);
                    fv.visitEnd();
                }

                // create getter for the entiy field
                // ex: TypeEntity getType() { return this.type; }
                // Note: we let this handle the panache-common code

                // create setter for the entity field
                // ex: void setType(TypeEntity p1) { this.type = p1; this.setType_id(p1.id); }
                // ex: void setType(List<TypeEntity> p1) { this.type = p1; this.setType_id(p1.stream().map().collect()) }
                String setterName = referencedField.getSetterName();
                String setterDescriptor = "(" + referencedField.descriptor + ")V";
                if (!userMethods.contains(setterName + "/" + setterDescriptor)) {
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC,
                            setterName, setterDescriptor, referencedField.signature == null ? null
                                    : "(" + referencedField.signature + ")V",
                            null);
                    mv.visitCode();
                    // this.field = param1;
                    mv.visitIntInsn(Opcodes.ALOAD, 0);
                    mv.visitIntInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), referencedField.name,
                            referencedField.descriptor);

                    if (!referencedField.isContainerWrapped) {
                        // this.field_id = param1.id
                        mv.visitIntInsn(Opcodes.ALOAD, 0);
                        mv.visitIntInsn(Opcodes.ALOAD, 1);
                        mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(referencedField.descriptor).getInternalName(),
                                referencedField.bsonIdFieldName,
                                referencedField.id_descriptor);
                        mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), referencedField.id_name,
                                referencedField.id_descriptor);
                    } else {
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), referencedField.id_name,
                                referencedField.id_descriptor);
                        mv.visitVarInsn(Opcodes.ASTORE, 2);

                        // this.field_id.clear()
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);

                        // for (e : param1) { this.field_id.push(e.id); }
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                                "()Ljava/util/Iterator", true);
                        mv.visitVarInsn(Opcodes.ASTORE, 3);

                        Label lab_loop_check = new Label();
                        Label lab_loop_body = new Label();
                        mv.visitJumpInsn(Opcodes.GOTO, lab_loop_check);

                        mv.visitLabel(lab_loop_body);

                        // iter.next().id
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getType(referencedField.entity_descriptor).getInternalName());
                        mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(referencedField.entity_descriptor).getInternalName(),
                                referencedField.bsonIdFieldName,
                                referencedField.id_descriptor);
                        mv.visitVarInsn(Opcodes.ASTORE, 4);

                        // this.field_id.push( iter.next().id )
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
                        mv.visitInsn(Opcodes.POP);

                        mv.visitLabel(lab_loop_check);
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                        mv.visitJumpInsn(Opcodes.IFNE, lab_loop_body);
                    }

                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }

            } else if (field instanceof ReferenceEntityField) {
                ReferenceEntityField refField = (ReferenceEntityField) field;

                // create getter for the id field
                // ex: void getType_id() { return this.type_id; }
                // Note: we let this handle the panache-common code

                // create setter for the id field
                // ex: void setType_id(ObjectId p1) { this.type_id = p1; this.type = TypeEntity.findById(p1); }
                String idSetterName = refField.getSetterName();
                String idSetterDescriptor = "(" + refField.descriptor + ")V";
                if (!userMethods.contains(idSetterName + "/" + idSetterDescriptor)) {
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC,
                            idSetterName, idSetterDescriptor, refField.signature == null ? null
                                    : "(" + refField.signature + ")V",
                            null);
                    mv.visitCode();

                    // this.field_id = p1;
                    mv.visitIntInsn(Opcodes.ALOAD, 0);
                    mv.visitIntInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), refField.name,
                            refField.descriptor);

                    if (!refField.isContainerWrapped) {
                        // this.field = Entity.findById(p1);
                        mv.visitIntInsn(Opcodes.ALOAD, 0);
                        mv.visitIntInsn(Opcodes.ALOAD, 1);
                        Type rType = Type.getType(refField.entity_descriptor);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                rType.getInternalName(), "findById",
                                "(Ljava/lang/Object;)Lio/quarkus/mongodb/panache/PanacheMongoEntityBase;", false);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, rType.getInternalName());
                        mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), refField.entity_name,
                                refField.entity_descriptor);
                    } else {
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), refField.entity_name,
                                refField.entity_descriptor);
                        mv.visitVarInsn(Opcodes.ASTORE, 2);

                        // this.field.clear()
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);

                        // for (id : p1) { this.field.add(TypeEntity.findById(id)); }
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                                "()Ljava/util/Iterator", true);
                        mv.visitVarInsn(Opcodes.ASTORE, 3);

                        Label lab_loop_check = new Label();
                        Label lab_loop_body = new Label();
                        mv.visitJumpInsn(Opcodes.GOTO, lab_loop_check);

                        mv.visitLabel(lab_loop_body);

                        // TypeEntity.findById( iter.next() )
                        {
                            // iter.next()
                            mv.visitVarInsn(Opcodes.ALOAD, 3);
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;",
                                    true);
                            // TypeEntity.findById( iter.next() )
                            Type rType = Type.getType(refField.real_entity_descriptor);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    rType.getInternalName(), "findById",
                                    "(Ljava/lang/Object;)Lio/quarkus/mongodb/panache/PanacheMongoEntityBase;", false);
                            mv.visitTypeInsn(Opcodes.CHECKCAST, rType.getInternalName());
                            mv.visitVarInsn(Opcodes.ASTORE, 4);
                        }

                        // this.field.push( TypeEntity.findById( iter.next() ) )
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
                        mv.visitInsn(Opcodes.POP);

                        mv.visitLabel(lab_loop_check);
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                        mv.visitJumpInsn(Opcodes.IFNE, lab_loop_body);
                    }

                    mv.visitInsn(Opcodes.RETURN);

                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }

            }
        }

    }

}
