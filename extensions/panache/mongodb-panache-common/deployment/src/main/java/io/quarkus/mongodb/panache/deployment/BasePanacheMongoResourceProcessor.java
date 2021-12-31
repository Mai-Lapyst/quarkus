package io.quarkus.mongodb.panache.deployment;

import static io.quarkus.deployment.util.JandexUtil.resolveTypeParameters;
import static io.quarkus.panache.common.deployment.PanacheConstants.META_INF_PANACHE_ARCHIVE_MARKER;
import static org.jboss.jandex.DotName.createSimple;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonIgnore;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.bson.types.ObjectId;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

import io.quarkus.arc.deployment.SyntheticBeansRuntimeInitBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.bootstrap.classloading.ClassPathElement;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.builder.BuildException;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.bean.JavaBeanUtil;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyIgnoreWarningBuildItem;
import io.quarkus.deployment.util.JandexUtil;
import io.quarkus.gizmo.DescriptorUtils;
import io.quarkus.jackson.spi.JacksonModuleBuildItem;
import io.quarkus.jsonb.spi.JsonbDeserializerBuildItem;
import io.quarkus.jsonb.spi.JsonbSerializerBuildItem;
import io.quarkus.mongodb.deployment.MongoClientNameBuildItem;
import io.quarkus.mongodb.deployment.MongoUnremovableClientsBuildItem;
import io.quarkus.mongodb.panache.common.MongoIndex;
import io.quarkus.mongodb.panache.common.PanacheMongoRecorder;
import io.quarkus.mongodb.panache.jackson.ObjectIdDeserializer;
import io.quarkus.mongodb.panache.jackson.ObjectIdSerializer;
import io.quarkus.panache.common.deployment.EntityField;
import io.quarkus.panache.common.deployment.EntityModel;
import io.quarkus.panache.common.deployment.MetamodelInfo;
import io.quarkus.panache.common.deployment.PanacheEntityClassesBuildItem;
import io.quarkus.panache.common.deployment.PanacheEntityEnhancer;
import io.quarkus.panache.common.deployment.PanacheFieldAccessEnhancer;
import io.quarkus.panache.common.deployment.PanacheMethodCustomizer;
import io.quarkus.panache.common.deployment.PanacheMethodCustomizerBuildItem;
import io.quarkus.panache.common.deployment.PanacheRepositoryEnhancer;
import io.quarkus.panache.common.deployment.TypeBundle;

public abstract class BasePanacheMongoResourceProcessor {
    public static final DotName BSON_ID = createSimple(BsonId.class.getName());
    public static final DotName BSON_IGNORE = createSimple(BsonIgnore.class.getName());
    public static final DotName BSON_PROPERTY = createSimple(BsonProperty.class.getName());
    public static final DotName MONGO_ENTITY = createSimple(io.quarkus.mongodb.panache.common.MongoEntity.class.getName());
    public static final DotName MONGO_REFERENCE = createSimple(
            io.quarkus.mongodb.panache.common.MongoReference.class.getName());
    public static final DotName MONGO_INDEX = createSimple(io.quarkus.mongodb.panache.common.MongoIndex.class.getName());
    public static final DotName OBJECT_ID = createSimple(ObjectId.class.getName());
    public static final DotName PROJECTION_FOR = createSimple(io.quarkus.mongodb.panache.common.ProjectionFor.class.getName());
    public static final String BSON_PACKAGE = "org.bson.";

    // public static final DotName ITERABLE = createSimple(java.lang.Iterable.class.getName());
    public static final DotName JAVA_COLLECTION = createSimple(java.util.Collection.class.getName());
    public static final DotName JAVA_LIST = createSimple(java.util.List.class.getName());
    //public static final DotName JAVA_MAP = createSimple(java.util.Map.class.getName());
    public static final DotName JAVA_OBJECT = createSimple(java.lang.Object.class.getName());

    //public static final DotName JAVA_BYTE = createSimple(java.lang.Byte.class.getName());
    //public static final DotName JAVA_SHORT = createSimple(java.lang.Short.class.getName());
    //public static final DotName JAVA_INTEGER = createSimple(java.lang.Integer.class.getName());
    //public static final DotName JAVA_LONG = createSimple(java.lang.Long.class.getName());
    //public static final DotName JAVA_STRING = createSimple(java.lang.String.class.getName());

    @BuildStep
    public void buildImperative(CombinedIndexBuildItem index,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy,
            BuildProducer<PropertyMappingClassBuildStep> propertyMappingClass,
            List<PanacheMethodCustomizerBuildItem> methodCustomizersBuildItems) throws BuildException {

        List<PanacheMethodCustomizer> methodCustomizers = methodCustomizersBuildItems.stream()
                .map(bi -> bi.getMethodCustomizer()).collect(Collectors.toList());

        MetamodelInfo modelInfo = new MetamodelInfo();
        processTypes(index, transformers, reflectiveClass, reflectiveHierarchy, propertyMappingClass, getImperativeTypeBundle(),
                createRepositoryEnhancer(index, methodCustomizers),
                createEntityEnhancer(index, methodCustomizers, modelInfo),
                modelInfo);
    }

    @BuildStep
    public void buildReactive(CombinedIndexBuildItem index,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy,
            BuildProducer<PropertyMappingClassBuildStep> propertyMappingClass,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            List<PanacheMethodCustomizerBuildItem> methodCustomizersBuildItems) throws BuildException {
        List<PanacheMethodCustomizer> methodCustomizers = methodCustomizersBuildItems.stream()
                .map(bi -> bi.getMethodCustomizer()).collect(Collectors.toList());

        MetamodelInfo modelInfo = new MetamodelInfo();
        processTypes(index, transformers, reflectiveClass, reflectiveHierarchy, propertyMappingClass, getReactiveTypeBundle(),
                createReactiveRepositoryEnhancer(index, methodCustomizers),
                createReactiveEntityEnhancer(index, methodCustomizers, modelInfo),
                modelInfo);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    protected void buildReplacementMap(List<PropertyMappingClassBuildStep> propertyMappingClasses, CombinedIndexBuildItem index,
            PanacheMongoRecorder recorder) {
        Map<String, Map<String, String>> replacementMap = new ConcurrentHashMap<>();
        for (PropertyMappingClassBuildStep classToMap : propertyMappingClasses) {
            DotName dotName = createSimple(classToMap.getClassName());
            ClassInfo classInfo = index.getIndex().getClassByName(dotName);
            if (classInfo != null) {
                // only compute field replacement for types inside the index
                Map<String, String> classReplacementMap = replacementMap.computeIfAbsent(classToMap.getClassName(),
                        className -> computeReplacement(classInfo));
                if (classToMap.getAliasClassName() != null) {
                    // also register the replacement map for the projection classes
                    replacementMap.put(classToMap.getAliasClassName(), classReplacementMap);
                }
            }
        }

        recorder.setReplacementCache(replacementMap);
    }

    private Map<String, String> computeReplacement(ClassInfo classInfo) {
        Map<String, String> replacementMap = new HashMap<>();
        for (FieldInfo field : classInfo.fields()) {
            AnnotationInstance bsonProperty = field.annotation(BSON_PROPERTY);
            if (bsonProperty != null) {
                replacementMap.put(field.name(), bsonProperty.value().asString());
            }
        }
        for (MethodInfo method : classInfo.methods()) {
            if (method.name().startsWith("get")) {
                // we try to replace also for getter
                AnnotationInstance bsonProperty = method.annotation(BSON_PROPERTY);
                if (bsonProperty != null) {
                    String fieldName = JavaBeanUtil.decapitalize(method.name().substring(3));
                    replacementMap.put(fieldName, bsonProperty.value().asString());
                }
            }
        }
        return replacementMap.isEmpty() ? Collections.emptyMap() : replacementMap;
    }

    protected abstract PanacheEntityEnhancer createEntityEnhancer(CombinedIndexBuildItem index,
            List<PanacheMethodCustomizer> methodCustomizers, MetamodelInfo modelInfo);

    protected abstract PanacheEntityEnhancer createReactiveEntityEnhancer(CombinedIndexBuildItem index,
            List<PanacheMethodCustomizer> methodCustomizers, MetamodelInfo modelInfo);

    protected abstract PanacheRepositoryEnhancer createReactiveRepositoryEnhancer(CombinedIndexBuildItem index,
            List<PanacheMethodCustomizer> methodCustomizers);

    protected abstract PanacheRepositoryEnhancer createRepositoryEnhancer(CombinedIndexBuildItem index,
            List<PanacheMethodCustomizer> methodCustomizers);

    private void extractMappings(Map<String, String> classPropertyMapping, ClassInfo target, CombinedIndexBuildItem index) {
        for (FieldInfo fieldInfo : target.fields()) {
            if (fieldInfo.hasAnnotation(BSON_PROPERTY)) {
                AnnotationInstance bsonProperty = fieldInfo.annotation(BSON_PROPERTY);
                classPropertyMapping.put(fieldInfo.name(), bsonProperty.value().asString());
            }
        }
        for (MethodInfo methodInfo : target.methods()) {
            if (methodInfo.hasAnnotation(BSON_PROPERTY)) {
                AnnotationInstance bsonProperty = methodInfo.annotation(BSON_PROPERTY);
                classPropertyMapping.put(methodInfo.name(), bsonProperty.value().asString());
            }
        }

        // climb up the hierarchy of types
        if (!target.superClassType().name().equals(JandexUtil.DOTNAME_OBJECT)) {
            Type superType = target.superClassType();
            ClassInfo superClass = index.getIndex().getClassByName(superType.name());
            extractMappings(classPropertyMapping, superClass, index);
        }
    }

    @BuildStep
    protected PanacheEntityClassesBuildItem findEntityClasses(List<PanacheMongoEntityClassBuildItem> entityClasses) {
        if (!entityClasses.isEmpty()) {
            Set<String> ret = new HashSet<>();
            for (PanacheMongoEntityClassBuildItem entityClass : entityClasses) {
                ret.add(entityClass.get().name().toString());
            }
            return new PanacheEntityClassesBuildItem(ret);
        }
        return null;
    }

    protected abstract TypeBundle getImperativeTypeBundle();

    protected abstract TypeBundle getReactiveTypeBundle();

    @BuildStep
    protected void handleProjectionFor(CombinedIndexBuildItem index,
            BuildProducer<PropertyMappingClassBuildStep> propertyMappingClass,
            BuildProducer<BytecodeTransformerBuildItem> transformers) {
        // manage @BsonProperty for the @ProjectionFor annotation
        Map<DotName, Map<String, String>> propertyMapping = new HashMap<>();
        for (AnnotationInstance annotationInstance : index.getIndex().getAnnotations(PROJECTION_FOR)) {
            Type targetClass = annotationInstance.value().asClass();
            ClassInfo target = index.getIndex().getClassByName(targetClass.name());
            Map<String, String> classPropertyMapping = new HashMap<>();
            extractMappings(classPropertyMapping, target, index);
            propertyMapping.put(targetClass.name(), classPropertyMapping);
        }
        for (AnnotationInstance annotationInstance : index.getIndex().getAnnotations(PROJECTION_FOR)) {
            Type targetClass = annotationInstance.value().asClass();
            Map<String, String> targetPropertyMapping = propertyMapping.get(targetClass.name());
            if (targetPropertyMapping != null && !targetPropertyMapping.isEmpty()) {
                ClassInfo info = annotationInstance.target().asClass();
                ProjectionForEnhancer fieldEnhancer = new ProjectionForEnhancer(targetPropertyMapping);
                transformers.produce(new BytecodeTransformerBuildItem(info.name().toString(), fieldEnhancer));
            }

            // Register for building the property mapping cache
            propertyMappingClass
                    .produce(new PropertyMappingClassBuildStep(targetClass.name().toString(),
                            annotationInstance.target().asClass().name().toString()));
        }
    }

    @BuildStep
    public void mongoClientNames(ApplicationArchivesBuildItem applicationArchivesBuildItem,
            BuildProducer<MongoClientNameBuildItem> mongoClientName) {
        Set<String> values = new HashSet<>();
        IndexView indexView = applicationArchivesBuildItem.getRootArchive().getIndex();
        Collection<AnnotationInstance> instances = indexView.getAnnotations(MONGO_ENTITY);
        for (AnnotationInstance annotation : instances) {
            AnnotationValue clientName = annotation.value("clientName");
            if ((clientName != null) && !clientName.asString().isEmpty()) {
                values.add(clientName.asString());
            }
        }
        for (String value : values) {
            // we don't want the qualifier @MongoClientName qualifier added
            // as these clients will only be looked up programmatically via name
            // see MongoOperations#mongoClient
            mongoClientName.produce(new MongoClientNameBuildItem(value, false));
        }
    }

    protected void processEntities(CombinedIndexBuildItem index,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<PropertyMappingClassBuildStep> propertyMappingClass,
            PanacheEntityEnhancer entityEnhancer, TypeBundle typeBundle,
            MetamodelInfo modelInfo) throws BuildException {

        Set<String> modelClasses = new HashSet<>();
        // Note that we do this in two passes because for some reason Jandex does not give us subtypes
        // of PanacheMongoEntity if we ask for subtypes of PanacheMongoEntityBase
        for (ClassInfo classInfo : index.getIndex().getAllKnownSubclasses(typeBundle.entityBase().dotName())) {
            if (classInfo.name().equals(typeBundle.entity().dotName())) {
                continue;
            }
            if (modelClasses.add(classInfo.name().toString()))
                modelInfo.addEntityModel(createEntityModel(index, classInfo, typeBundle));
        }
        for (ClassInfo classInfo : index.getIndex().getAllKnownSubclasses(typeBundle.entity().dotName())) {
            if (modelClasses.add(classInfo.name().toString()))
                modelInfo.addEntityModel(createEntityModel(index, classInfo, typeBundle));
        }

        // iterate over all the entity classes
        for (String modelClass : modelClasses) {
            transformers.produce(new BytecodeTransformerBuildItem(modelClass, entityEnhancer));

            //register for reflection entity classes
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, true, modelClass));

            // Register for building the property mapping cache
            propertyMappingClass.produce(new PropertyMappingClassBuildStep(modelClass));
        }

        replaceFieldAccesses(transformers, modelInfo);
    }

    private void replaceFieldAccesses(BuildProducer<BytecodeTransformerBuildItem> transformers, MetamodelInfo modelInfo) {
        Set<String> entitiesWithPublicFields = modelInfo.getEntitiesWithPublicFields();
        if (entitiesWithPublicFields.isEmpty()) {
            // There are no public fields to be accessed in the first place.
            return;
        }

        Set<String> entityClassNamesInternal = new HashSet<>();
        for (String entityClassName : entitiesWithPublicFields) {
            entityClassNamesInternal.add(entityClassName.replace(".", "/"));
        }

        PanacheFieldAccessEnhancer panacheFieldAccessEnhancer = new PanacheFieldAccessEnhancer(modelInfo);
        QuarkusClassLoader tccl = (QuarkusClassLoader) Thread.currentThread().getContextClassLoader();
        Set<String> produced = new HashSet<>();

        for (ClassPathElement i : tccl.getElementsWithResource(META_INF_PANACHE_ARCHIVE_MARKER)) {
            for (String res : i.getProvidedResources()) {
                if (res.endsWith(".class")) {
                    String cn = res.replace("/", ".").substring(0, res.length() - 6);
                    if (produced.contains(cn)) {
                        continue;
                    }
                    produced.add(cn);
                    transformers.produce(
                            new BytecodeTransformerBuildItem(cn, panacheFieldAccessEnhancer, entityClassNamesInternal));
                }
            }
        }
    }

    private boolean findTypeInSupertypes(DotName search, Type searchIn, CombinedIndexBuildItem index) {
        Type currentType = searchIn;
        //System.out.printf("findTypeInSupertypes(): search='%s'; searchIn='%s'\n", search, searchIn.name());
        while (currentType != null && !currentType.name().equals(JAVA_OBJECT)) {
            //System.out.printf("findTypeInSupertypes(): - currentType: %s\n", currentType.name());
            if (currentType.name().equals(search)) {
                return true;
            }
            ClassInfo classInfo = index.getIndex().getClassByName(currentType.name());
            if (classInfo == null) {
                classInfo = index.getComputingIndex().getClassByName(currentType.name());
                if (classInfo == null) {
                    break;
                }
            }
            for (Type interfaceType : classInfo.interfaceTypes()) {
                //System.out.printf("findTypeInSupertypes():   - interface %s (%s)\n", interfaceType.name(),
                //        interfaceType.getClass().getName());
                if (interfaceType.name().equals(search)) {
                    return true;
                }
                if (findTypeInSupertypes(search, interfaceType, index)) {
                    return true;
                }
            }
            currentType = classInfo.superClassType();
        }
        return false;
    }

    // private boolean isPrimitiveClassType(Type type) {
    //     if (!(type instanceof ClassType)) {
    //         return false;
    //     }
    //     DotName name = type.name();
    //     return name.equals(JAVA_BYTE) || name.equals(JAVA_SHORT) || name.equals(JAVA_INTEGER) || name.equals(JAVA_LONG)
    //             || name.equals(JAVA_STRING);
    // }

    public static class EntityCheckResult {
        public final Type entityType;
        public final boolean isContainerWrapped;
        public final boolean hasPredefinedIdField;

        public EntityCheckResult(Type t, boolean b1, boolean b2) {
            this.entityType = t;
            this.isContainerWrapped = b1;
            this.hasPredefinedIdField = b2;
        }
    }

    private EntityCheckResult checkIfTypeIsEntity(Type typeToCheck, CombinedIndexBuildItem index, TypeBundle typeBundle) {
        Type entityType = typeToCheck;
        boolean isContainerWrapped = false;
        boolean hasPredefinedIdField = false;
        while (true) {
            System.out.printf("- test %s for entitytype...\n", entityType.name());
            if (entityType instanceof ArrayType) {
                isContainerWrapped = true;
                entityType = entityType.asArrayType().component();
                continue;
            } else if (entityType instanceof ParameterizedType) {
                // TODO: types like 'Y<String>.X' where X might be a entity to reference are NOT supported

                ParameterizedType genericType = (ParameterizedType) entityType;

                // check if type itself is one of out list-ish interfaces
                // findTypeInSupertypes(JAVA_COLLECTION, entityType, index)
                // entityType.name().equals(JAVA_COLLECTION)
                if (entityType.name().equals(JAVA_LIST)) {
                    isContainerWrapped = true;
                    entityType = genericType.arguments().get(0);
                    continue;
                    // } else if (entityType.name().equals(JAVA_MAP) || findTypeInSupertypes(JAVA_MAP, entityType, index)) {
                    //     Type keyType = genericType.arguments().get(0);
                    //     if (keyType instanceof PrimitiveType || isPrimitiveClassType(keyType)) {
                    //         isContainerWrapped = true;
                    //         entityType = genericType.arguments().get(1);
                    //         continue;
                    //     } else if (keyType instanceof ClassType) {
                    //         EntityCheckResult result = this.checkIfTypeIsEntity(keyType, index, typeBundle)
                    //     } else {
                    //         entityType = null;
                    //         break;
                    //     }
                } else {
                    entityType = null;
                    break;
                }
            } else if (entityType instanceof ClassType) {
                ClassInfo cInfo = index.getIndex().getClassByName(entityType.name());
                if (cInfo == null) {
                    // class seems not in the jandex index... ignore it
                    entityType = null;
                    break;
                }
                if (cInfo.superName() == null) {
                    entityType = null;
                    break;
                }
                boolean extendsMongoEntity = cInfo.superName().equals(typeBundle.entity().dotName());
                if (cInfo.superName().equals(typeBundle.entityBase().dotName()) || extendsMongoEntity) {
                    isContainerWrapped = true;
                    hasPredefinedIdField = extendsMongoEntity;
                    break;
                } else {
                    entityType = null;
                    break;
                }
            } else {
                entityType = null;
                break;
            }
        }
        System.out.printf("=> found entitytype: %s\n", entityType == null ? "null" : entityType.name());
        return new EntityCheckResult(entityType, isContainerWrapped, hasPredefinedIdField);
    }

    private Type createReferenceType(Type containerOrEntityType, Type entityType, DotName idFieldDotName,
            CombinedIndexBuildItem index, Map<String, String> typeMappings) throws BuildException {
        if (containerOrEntityType instanceof ArrayType) {
            ArrayType arrayType = containerOrEntityType.asArrayType();
            Type type = createReferenceType(arrayType.component(), entityType, idFieldDotName, index, typeMappings);
            return ArrayType.create(type, arrayType.dimensions());
        } else if (containerOrEntityType instanceof ParameterizedType) {
            ParameterizedType genericType = containerOrEntityType.asParameterizedType();

            ClassInfo classInfo = index.getIndex().getClassByName(genericType.name());
            if (classInfo == null) {
                classInfo = index.getComputingIndex().getClassByName(genericType.name());
            }
            if (Modifier.isInterface(classInfo.flags()) || Modifier.isAbstract(classInfo.flags())) {
                // need explicit type for creation
                Type t = Type.create(genericType.name(), Type.Kind.CLASS);
                if (!typeMappings.containsKey(DescriptorUtils.typeToString(t))) {
                    throw new BuildException(
                            "Referenced field needs explicit mapping to create-able class for '" + genericType.name() + "'",
                            Collections.emptyList());
                }
            }

            // findTypeInSupertypes(JAVA_COLLECTION, genericType, index)
            if (genericType.name().equals(JAVA_COLLECTION) || genericType.name().equals(JAVA_LIST)) {
                Type t = createReferenceType(genericType.arguments().get(0), entityType, idFieldDotName, index, typeMappings);
                return ParameterizedType.create(genericType.name(), new Type[] { t }, genericType.owner());
            }
            // else if (genericType.name().equals(JAVA_MAP) || findTypeInSupertypes(JAVA_MAP, genericType, index)) {
            //     Type keyType = genericType.arguments().get(0);
            //     Type valueType = createReferenceType(genericType.arguments().get(1), entityType, idFieldDotName, index,
            //             typeMappings);
            //     return ParameterizedType.create(genericType.name(), new Type[] { keyType, valueType }, genericType.owner());
            // }
        } else {
            if (containerOrEntityType == entityType) {
                return Type.create(idFieldDotName, Type.Kind.CLASS);
            }
        }
        return null;
    }

    private EntityModel createEntityModel(CombinedIndexBuildItem index, ClassInfo classInfo, TypeBundle typeBundle)
            throws BuildException {
        Map<String, DotName> referenceFields = new HashMap<>();

        // TODO: test for @MongoReference annotation on non Mongo-Entity classes (?)
        // TODO: the referenced field extension currently only supports the active-record pattern
        // TODO: move some of the validity checks from this method into the validate() build step at the end of this class
        // TODO: add config option to determine if we should reference instead of embedd fields that type extends Mono-Entity classes or have the @MongoEntity annotation

        EntityModel entityModel = new EntityModel(classInfo);
        for (FieldInfo fieldInfo : classInfo.fields()) {
            String name = fieldInfo.name();

            if (referenceFields.containsKey(name)) {
                DotName bsonIdFieldDotName = referenceFields.get(name);

                // field was already added by the generation of an referenced field!
                // we test for the correct type
                if (!fieldInfo.type().name().equals(bsonIdFieldDotName)) {
                    throw new BuildException("Cannot define field '" + name
                            + "' with another type as " + bsonIdFieldDotName.toString()
                            + " because it is used as reference holder for a referenced field",
                            Collections.emptyList());
                }

                if (fieldInfo.hasAnnotation(BSON_IGNORE)) {
                    throw new BuildException("Cannot annotate field '" + name
                            + "' with @org.bson.codecs.pojo.annotations.BsonIgnore because it is used as reference holder for a referenced field",
                            Collections.emptyList());
                }

                // skip it because it *should* not contain any important data but is just defined so the user can access it while developing
                continue;
            }

            if (Modifier.isPublic(fieldInfo.flags())
                    && !Modifier.isStatic(fieldInfo.flags())
                    && !fieldInfo.hasAnnotation(BSON_IGNORE)) {

                System.out.printf(
                        "Create Entity Model for class %s: field '%s' of type '%s' (%s)\n",
                        classInfo.name(), name, fieldInfo.type().name(), DescriptorUtils.typeToString(fieldInfo.type()));

                Type fieldType = fieldInfo.type();
                EntityCheckResult result = checkIfTypeIsEntity(fieldType, index, typeBundle);
                if (result.entityType != null) {
                    ClassInfo fieldClassInfo = index.getIndex().getClassByName(result.entityType.name());
                    if (fieldClassInfo != null) {
                        boolean extendsMongoEntityBase = fieldClassInfo.superName().equals(typeBundle.entityBase().dotName());
                        boolean extendsMongoEntity = fieldClassInfo.superName().equals(typeBundle.entity().dotName());

                        if (extendsMongoEntityBase || extendsMongoEntity
                                || fieldClassInfo.classAnnotation(MONGO_ENTITY) != null) {
                            // Field's class is an entity, reference it instead of embedding it

                            String fieldname = null;
                            Map<String, String> typeMappings = new HashMap<>();
                            if (fieldInfo.hasAnnotation(MONGO_REFERENCE)) {
                                AnnotationInstance ref = fieldInfo.annotation(MONGO_REFERENCE);
                                if (ref.value("store_in") != null) {
                                    fieldname = ref.value("store_in").asString();
                                }

                                if (ref.value("typeMappings") != null) {
                                    for (AnnotationInstance mapping : ref.value("typeMappings").asNestedArray()) {
                                        if (mapping.value("type") != null && mapping.value("mapped") != null) {
                                            Type type = Type.create(DotName.createSimple(mapping.value("type").asString()),
                                                    Type.Kind.CLASS);
                                            Type mapped = Type.create(DotName.createSimple(mapping.value("mapped").asString()),
                                                    Type.Kind.CLASS);
                                            typeMappings.put(DescriptorUtils.typeToString(type),
                                                    DescriptorUtils.typeToString(mapped));
                                        }
                                    }
                                }
                            }
                            if (fieldname == null || fieldname.trim().isBlank()) {
                                fieldname = name + "_id";
                            }

                            if (referenceFields.containsKey(fieldname)) {
                                // already referenced by another field; error
                                throw new BuildException(
                                        "Cannot set the name of the field that holds the reference (the id of referenced entity) because another field already uses this name!",
                                        Collections.emptyList());
                            }

                            // find the bsonId field from the referenced entity
                            String bsonIdFieldName = null;
                            String bsonIdFieldDescriptor = null;

                            if (extendsMongoEntity || result.hasPredefinedIdField) {
                                // this shortcut is possible because mongodb-panache already checks and requires (!) that only one @BsonId is defined
                                bsonIdFieldName = "id";
                                bsonIdFieldDescriptor = DescriptorUtils.typeToString(Type.create(OBJECT_ID, Type.Kind.CLASS));
                            } else {
                                // NOTE: this uses the first field with @BsonId it finds
                                for (FieldInfo fieldInf : fieldClassInfo.fields()) {
                                    if (fieldInf.hasAnnotation(BSON_ID)) {
                                        bsonIdFieldName = fieldInf.name();
                                        bsonIdFieldDescriptor = DescriptorUtils.typeToString(fieldInf.type());
                                        break;
                                    }
                                }
                                if (bsonIdFieldName == null) {
                                    FieldInfo idFieldInfo = fieldClassInfo.field("id");
                                    if (idFieldInfo == null) {
                                        throw new BuildException(
                                                "Cannot reference the POJO '" + fieldInfo.type().name()
                                                        + "' because it has no valid id field for mongodb.",
                                                Collections.emptyList());
                                    }
                                    bsonIdFieldName = "id";
                                    bsonIdFieldDescriptor = DescriptorUtils.typeToString(idFieldInfo.type());
                                }
                            }

                            DotName bsonIdFieldDotName = DotName
                                    .createSimple(org.objectweb.asm.Type.getType(bsonIdFieldDescriptor).getClassName());

                            // generate referenceField descriptor based on the fieldType and the bsonIdFieldDescriptor
                            Type referenceFieldType = createReferenceType(fieldType, result.entityType, bsonIdFieldDotName,
                                    index,
                                    typeMappings);
                            String referenceFieldDescriptor = DescriptorUtils.typeToString(referenceFieldType);

                            // NOTE: we generate two field here for a couple of reasons:
                            //         1. we dont need to re-implement the field-to-method replacement code from panache

                            // create a referenced field
                            ReferencedEntityField entityField = new ReferencedEntityField(
                                    name, DescriptorUtils.typeToString(fieldInfo.type()),
                                    fieldname, referenceFieldDescriptor, bsonIdFieldName, result.isContainerWrapped,
                                    DescriptorUtils.typeToString(result.entityType), typeMappings);
                            entityField.id_field_exists = classInfo.field(fieldname) != null;
                            entityModel.addField(entityField);

                            // create the field that stores the id
                            ReferenceEntityField idField = new ReferenceEntityField(
                                    fieldname, referenceFieldDescriptor,
                                    name, DescriptorUtils.typeToString(fieldInfo.type()), bsonIdFieldName,
                                    result.isContainerWrapped, DescriptorUtils.typeToString(result.entityType));

                            if (entityModel.fields.containsKey(fieldname)) {
                                FieldInfo fInfo = classInfo.field(fieldname);
                                if (!fInfo.type().name().equals(bsonIdFieldDotName)) {
                                    throw new BuildException("Cannot define field '" + fieldname
                                            + "' with another type as " + bsonIdFieldDotName.toString()
                                            + " because it is used as reference holder for a referenced field",
                                            Collections.emptyList());
                                }

                                if (fInfo.hasAnnotation(BSON_IGNORE)) {
                                    throw new BuildException("Cannot annotate field '" + fieldname
                                            + "' with @org.bson.codecs.pojo.annotations.BsonIgnore because it is used as reference holder for a referenced field",
                                            Collections.emptyList());
                                }

                                entityModel.fields.replace(idField.name, idField);
                            } else {
                                entityModel.addField(idField);
                            }

                            referenceFields.put(fieldname, bsonIdFieldDotName);
                            continue;
                        }
                    }
                }

                entityModel.addField(new EntityField(name, DescriptorUtils.typeToString(fieldInfo.type())));
            }
        }
        return entityModel;
    }

    protected void processRepositories(CombinedIndexBuildItem index,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy,
            BuildProducer<PropertyMappingClassBuildStep> propertyMappingClass,
            PanacheRepositoryEnhancer repositoryEnhancer, TypeBundle typeBundle) {

        Set<String> daoClasses = new HashSet<>();
        Set<Type> daoTypeParameters = new HashSet<>();
        DotName dotName = typeBundle.repositoryBase().dotName();
        for (ClassInfo classInfo : index.getIndex().getAllKnownImplementors(dotName)) {
            // Skip PanacheMongoRepository and abstract repositories
            if (classInfo.name().equals(typeBundle.repository().dotName()) || repositoryEnhancer.skipRepository(classInfo)) {
                continue;
            }
            daoClasses.add(classInfo.name().toString());
            daoTypeParameters.addAll(
                    resolveTypeParameters(classInfo.name(), typeBundle.repositoryBase().dotName(), index.getIndex()));
        }
        for (ClassInfo classInfo : index.getIndex().getAllKnownImplementors(typeBundle.repository().dotName())) {
            if (repositoryEnhancer.skipRepository(classInfo)) {
                continue;
            }
            daoClasses.add(classInfo.name().toString());
            daoTypeParameters.addAll(
                    resolveTypeParameters(classInfo.name(), typeBundle.repositoryBase().dotName(), index.getIndex()));
        }
        for (String daoClass : daoClasses) {
            transformers.produce(new BytecodeTransformerBuildItem(daoClass, repositoryEnhancer));
        }

        for (Type parameterType : daoTypeParameters) {
            // Register for reflection the type parameters of the repository: this should be the entity class and the ID class
            reflectiveHierarchy.produce(new ReflectiveHierarchyBuildItem.Builder().type(parameterType).build());

            // Register for building the property mapping cache
            propertyMappingClass.produce(new PropertyMappingClassBuildStep(parameterType.name().toString()));
        }
    }

    protected void processTypes(CombinedIndexBuildItem index,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy,
            BuildProducer<PropertyMappingClassBuildStep> propertyMappingClass,
            TypeBundle typeBundle, PanacheRepositoryEnhancer repositoryEnhancer,
            PanacheEntityEnhancer entityEnhancer, MetamodelInfo modelInfo) throws BuildException {
        processRepositories(index, transformers, reflectiveHierarchy, propertyMappingClass,
                repositoryEnhancer, typeBundle);
        processEntities(index, transformers, reflectiveClass, propertyMappingClass,
                entityEnhancer, typeBundle, modelInfo);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    protected void processMongodbIndexes(List<PropertyMappingClassBuildStep> propertyMappingClasses,
            CombinedIndexBuildItem index,
            PanacheMongoRecorder recorder) throws BuildException {

        List<String> entityClassnameList = new LinkedList<>();
        for (PropertyMappingClassBuildStep classToMap : propertyMappingClasses) {
            DotName dotName = createSimple(classToMap.getClassName());
            ClassInfo classInfo = index.getIndex().getClassByName(dotName);
            if (classInfo != null) {
                entityClassnameList.add(classToMap.getClassName());
            }
        }

        recorder.setEntityClassnameCache(entityClassnameList);
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    @Consume(SyntheticBeansRuntimeInitBuildItem.class)
    protected void ensureMongodbIndexes(PanacheMongoRecorder recorder) {
        recorder.ensureIndexes();
    }

    @BuildStep
    ReflectiveHierarchyIgnoreWarningBuildItem ignoreBsonTypes() {
        return new ReflectiveHierarchyIgnoreWarningBuildItem(dotname -> dotname.toString().startsWith(BSON_PACKAGE));
    }

    @BuildStep
    protected void registerJacksonSerDeser(BuildProducer<JacksonModuleBuildItem> customSerDeser) {
        customSerDeser.produce(
                new JacksonModuleBuildItem.Builder("ObjectIdModule")
                        .add(ObjectIdSerializer.class.getName(),
                                ObjectIdDeserializer.class.getName(),
                                ObjectId.class.getName())
                        .build());
    }

    @BuildStep
    protected void registerJsonbSerDeser(BuildProducer<JsonbSerializerBuildItem> jsonbSerializers,
            BuildProducer<JsonbDeserializerBuildItem> jsonbDeserializers) {
        jsonbSerializers
                .produce(new JsonbSerializerBuildItem(io.quarkus.mongodb.panache.jsonb.ObjectIdSerializer.class.getName()));
        jsonbDeserializers
                .produce(new JsonbDeserializerBuildItem(io.quarkus.mongodb.panache.jsonb.ObjectIdDeserializer.class.getName()));
    }

    @BuildStep
    public void unremovableClients(BuildProducer<MongoUnremovableClientsBuildItem> unremovable) {
        unremovable.produce(new MongoUnremovableClientsBuildItem());
    }

    @BuildStep
    protected ValidationPhaseBuildItem.ValidationErrorBuildItem validate(ValidationPhaseBuildItem validationPhase,
            CombinedIndexBuildItem index) throws BuildException {
        // we verify that no ID fields are defined (via @BsonId) when extending PanacheMongoEntity or ReactivePanacheMongoEntity
        for (AnnotationInstance annotationInstance : index.getIndex().getAnnotations(BSON_ID)) {
            ClassInfo info = JandexUtil.getEnclosingClass(annotationInstance);
            if (JandexUtil.isSubclassOf(index.getIndex(), info,
                    getImperativeTypeBundle().entity().dotName())) {
                BuildException be = new BuildException("You provide a MongoDB identifier via @BsonId inside '" + info.name() +
                        "' but one is already provided by PanacheMongoEntity, " +
                        "your class should extend PanacheMongoEntityBase instead, or use the id provided by PanacheMongoEntity",
                        Collections.emptyList());
                return new ValidationPhaseBuildItem.ValidationErrorBuildItem(be);
            } else if (JandexUtil.isSubclassOf(index.getIndex(), info,
                    getReactiveTypeBundle().entity().dotName())) {
                BuildException be = new BuildException("You provide a MongoDB identifier via @BsonId inside '" + info.name() +
                        "' but one is already provided by ReactivePanacheMongoEntity, " +
                        "your class should extend ReactivePanacheMongoEntityBase instead, or use the id provided by ReactivePanacheMongoEntity",
                        Collections.emptyList());
                return new ValidationPhaseBuildItem.ValidationErrorBuildItem(be);
            }
        }
        return null;
    }
}
