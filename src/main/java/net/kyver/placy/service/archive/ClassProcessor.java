package net.kyver.placy.service.archive;

import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ClassProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ClassProcessor.class);

    private final ConcurrentMap<String, byte[]> classCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;

    public byte[] replacePlaceholdersInClass(byte[] classBytes, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return classBytes;
        }

        String cacheKey = createCacheKey(classBytes, replacements);

        if (classCache.size() > MAX_CACHE_SIZE) {
            classCache.clear();
            logger.debug("Class cache cleared due to size limit");
        }

        return classCache.computeIfAbsent(cacheKey, key -> {
            try {
                byte[] transformedBytes = transformClass(classBytes, replacements);
                if (transformedBytes != null && transformedBytes.length > 0) {
                    return transformedBytes;
                } else {
                    logger.warn("Class transformation returned empty/null result, using original bytes");
                    return classBytes;
                }
            } catch (Exception e) {
                logger.error("Class transformation failed: {}, using original bytes", e.getMessage());
                return classBytes;
            }
        });
    }

    private byte[] transformClass(byte[] classBytes, Map<String, String> replacements) {
        ClassReader classReader = new ClassReader(classBytes);
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

        PlaceholderClassVisitor classVisitor = new PlaceholderClassVisitor(classWriter, replacements);
        classReader.accept(classVisitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return classWriter.toByteArray();
    }

    private String createCacheKey(byte[] classBytes, Map<String, String> replacements) {
        int classHash = java.util.Arrays.hashCode(classBytes);
        int replacementHash = replacements.hashCode();
        return classHash + ":" + replacementHash;
    }

    public void clearCache() {
        classCache.clear();
        logger.debug("Class processor cache cleared");
    }

    private static class PlaceholderClassVisitor extends ClassVisitor {
        private final Map<String, String> replacements;

        public PlaceholderClassVisitor(ClassWriter classWriter, Map<String, String> replacements) {
            super(Opcodes.ASM9, classWriter);
            this.replacements = replacements;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new PlaceholderMethodVisitor(methodVisitor, replacements);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                     String signature, Object value) {
            Object transformedValue = value;
            if (value instanceof String stringValue) {
                transformedValue = applyReplacements(stringValue, replacements);
            }
            return super.visitField(access, name, descriptor, signature, transformedValue);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            AnnotationVisitor annotationVisitor = super.visitAnnotation(descriptor, visible);
            return new PlaceholderAnnotationVisitor(annotationVisitor, replacements);
        }
    }

    private static class PlaceholderMethodVisitor extends MethodVisitor {
        private final Map<String, String> replacements;

        public PlaceholderMethodVisitor(MethodVisitor methodVisitor, Map<String, String> replacements) {
            super(Opcodes.ASM9, methodVisitor);
            this.replacements = replacements;
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String stringValue) {
                String transformedValue = applyReplacements(stringValue, replacements);
                super.visitLdcInsn(transformedValue);
            } else {
                super.visitLdcInsn(value);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            AnnotationVisitor annotationVisitor = super.visitAnnotationDefault();
            return new PlaceholderAnnotationVisitor(annotationVisitor, replacements);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            AnnotationVisitor annotationVisitor = super.visitAnnotation(descriptor, visible);
            return new PlaceholderAnnotationVisitor(annotationVisitor, replacements);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            AnnotationVisitor annotationVisitor = super.visitParameterAnnotation(parameter, descriptor, visible);
            return new PlaceholderAnnotationVisitor(annotationVisitor, replacements);
        }
    }

    private static class PlaceholderAnnotationVisitor extends AnnotationVisitor {
        private final Map<String, String> replacements;

        public PlaceholderAnnotationVisitor(AnnotationVisitor annotationVisitor, Map<String, String> replacements) {
            super(Opcodes.ASM9, annotationVisitor);
            this.replacements = replacements;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof String stringValue) {
                String transformedValue = applyReplacements(stringValue, replacements);
                super.visit(name, transformedValue);
            } else {
                super.visit(name, value);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            AnnotationVisitor annotationVisitor = super.visitAnnotation(name, descriptor);
            return new PlaceholderAnnotationVisitor(annotationVisitor, replacements);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor annotationVisitor = super.visitArray(name);
            return new PlaceholderAnnotationVisitor(annotationVisitor, replacements);
        }
    }

    private static String applyReplacements(String original, Map<String, String> replacements) {
        if (original == null || replacements.isEmpty()) {
            return original;
        }

        final AtomicReference<String>[] result = new AtomicReference[]{new AtomicReference<>(original)};
        replacements.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
                .forEach(entry -> {
                    if (result[0].get().contains(entry.getKey())) {
                        result[0].set(result[0].get().replace(entry.getKey(), entry.getValue()));
                    }
                });
        return result[0].get();
    }
}
