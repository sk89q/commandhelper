package com.laytonsmith.PureUtilities.Common.Annotations;

import com.laytonsmith.PureUtilities.ClassLoading.ClassDiscovery;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.AbstractMethodMirror;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.ClassReferenceMirror;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.ConstructorMirror;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.MethodMirror;
import com.laytonsmith.PureUtilities.Common.StringUtils;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 *
 */
public final class AggressiveDeprecationTransformer implements ClassFileTransformer {

	public byte[] transform(final InputStream input) throws IllegalClassFormatException, IOException {
		final ClassReader classReader = new ClassReader(input);
		ClassWriter classWriter = new ClassWriter(classReader, 0);
		doAccept(classWriter, classReader);
		return classWriter.toByteArray();
	}

	private void doAccept(final ClassWriter classWriter, final ClassReader classReader)
			throws IllegalClassFormatException {
		try {
			classReader.accept(new TranslatingClassVisitor(classWriter), 0);
		} catch(RuntimeException e) {
			final Throwable cause = e.getCause();
			if(cause instanceof IllegalClassFormatException) {
				throw (IllegalClassFormatException) cause;
			}
			throw e;
		}
	}

	private class TranslatingClassVisitor extends ClassVisitor {

		ClassReferenceMirror<?> lastClass;
		public TranslatingClassVisitor(final ClassWriter classWriter) {
			super(Opcodes.ASM9, classWriter);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			super.visit(version, access, name, signature, superName, interfaces);
			lastClass = new ClassReferenceMirror(Type.getObjectType(name).getDescriptor());
		}


		@Override
		public MethodVisitor visitMethod(int access, final String name, String methodDesc, final String signature,
				final String[] exceptions) {
			String methodDescLookup = methodDesc;
			if(ConstructorMirror.INIT.matches(name)) {
				// We have to do this replacement in order to match the lookup correctly, since the rewrite is done
				// like this in ClassDiscovery as well.
				methodDescLookup = StringUtils.replaceLast(methodDesc, "V", lastClass.getJVMName());
			}
			// We just use this method to construct the mirror enough to easily compare, but we need the MethodMirror
			// from class discovery for it to contain all the information.
			AbstractMethodMirror mirror = AbstractMethodMirror.fromVisitParameters(access, name, methodDescLookup, signature, exceptions, lastClass);
			for(MethodMirror m : ClassDiscovery.getDefaultInstance().getMethodsWithAnnotation(AggressiveDeprecation.class)) {
				if(m.getDeclaringClass().equals(mirror.getDeclaringClass())) {
					if(m.getName().equals(mirror.getName())) {
						if(m.getParams().equals(mirror.getParams())) {
							mirror = m;
							break;
						}
					}
				}
			}

			if(mirror.getAnnotation(AggressiveDeprecation.class) != null) {
				System.out.println(mirror.getDeclaringClass() + "." + mirror.getName() + "(" + mirror.getParams() + ") needs rewrite");
				access = access | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC;
			}
			return new MethodVisitor(Opcodes.ASM9,
					super.visitMethod(access, name, methodDesc, signature, exceptions)){};
		}
	}
}
