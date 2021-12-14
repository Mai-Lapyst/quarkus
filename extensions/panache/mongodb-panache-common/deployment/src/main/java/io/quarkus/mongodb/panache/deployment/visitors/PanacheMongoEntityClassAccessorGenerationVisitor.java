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

    private void generateMongoValidateReferencedFieldsMethod() {
        MethodVisitor mv = super.visitMethod(Modifier.PRIVATE, "mongoValidateReferencedFields", "()V", null,
                new String[] {});
        mv.visitCode();

        for (EntityField entityField : fields.values()) {
            if (entityField instanceof ReferencedEntityField) {
                ReferencedEntityField field = (ReferencedEntityField) entityField;

                // localEntity = this.type
                mv.visitIntInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), field.name, field.descriptor);
                mv.visitVarInsn(Opcodes.ASTORE, 1);

                // localId = this.type_id
                mv.visitIntInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, thisClass.getInternalName(), field.id_name, field.id_descriptor);
                mv.visitVarInsn(Opcodes.ASTORE, 2);

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

                Label lab_a = new Label();
                Label lab_b = new Label();
                Label lab_end = new Label();
                Label lab_check_ids_ok = new Label();

                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitJumpInsn(Opcodes.IFNULL, lab_a);

                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitJumpInsn(Opcodes.IFNULL, lab_b);

                {
                    // check_ids
                    // if (localEntity.id != localId) => error
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(field.descriptor).getInternalName(), field.bsonIdFieldName,
                            field.id_descriptor);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getType(field.id_descriptor).getInternalName(), "equals",
                            "(Ljava/lang/Object;)Z", false);
                    mv.visitJumpInsn(Opcodes.IFNE, lab_check_ids_ok);
                    {
                        Type exceptionType = Type.getType("Ljava/lang/RuntimeException;");
                        mv.visitTypeInsn(Opcodes.NEW, exceptionType.getInternalName());
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitLdcInsn("Detected inconsitency for referenced field: '" + field.name + "."
                                + field.bsonIdFieldName + "' and '"
                                + field.id_name + "' dont have the same value");
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, exceptionType.getInternalName(), "<init>",
                                "(Ljava/lang/String;)V", false);
                        mv.visitInsn(Opcodes.ATHROW);
                    }
                    mv.visitLabel(lab_check_ids_ok);
                }
                mv.visitJumpInsn(Opcodes.GOTO, lab_end);

                mv.visitLabel(lab_b);
                {
                    // set id field
                    // ex: this.type_id = localEntity.id
                    mv.visitIntInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(field.descriptor).getInternalName(), field.bsonIdFieldName,
                            field.id_descriptor);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), field.id_name, field.id_descriptor);
                }
                mv.visitJumpInsn(Opcodes.GOTO, lab_end);

                mv.visitLabel(lab_a);
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

                mv.visitLabel(lab_end);
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

                    // this.field_id = param1.id
                    mv.visitIntInsn(Opcodes.ALOAD, 0);
                    mv.visitIntInsn(Opcodes.ALOAD, 1);
                    mv.visitFieldInsn(Opcodes.GETFIELD, Type.getType(referencedField.descriptor).getInternalName(),
                            referencedField.bsonIdFieldName,
                            referencedField.id_descriptor);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, thisClass.getInternalName(), referencedField.id_name,
                            referencedField.id_descriptor);

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

                    mv.visitInsn(Opcodes.RETURN);

                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }

            }
        }

    }

}
