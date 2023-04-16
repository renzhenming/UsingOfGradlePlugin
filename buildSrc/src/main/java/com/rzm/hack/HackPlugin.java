package com.rzm.hack;

import com.android.build.gradle.AppExtension;
import com.android.build.gradle.AppPlugin;
import com.android.build.gradle.api.ApplicationVariant;

import org.apache.commons.compress.utils.IOUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class HackPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        System.out.println("RzmClassHack apply");

        //AppPlugin是android插件com.android.application的实现，默认是无法引用的，
        //需要导入
        if (!project.getPlugins().hasPlugin(AppPlugin.class)) {
            throw new GradleException("无法在非android application插件中使用此插件");
        }
        //创建一个hack{}做动态配置，提供一些可以按个人需求而配置的选项
        //就和引入了 apply plugin: 'com.android.application' 一样，android{}中配置各种参数
        HackExtension hack = project.getExtensions().create("hack", HackExtension.class);
        //这样直接拿配置是拿不到的，因为刚刚执行到apply plugin :'xxx'就执行apply方法了，配置还没准备好
        System.out.println("hack exclude path = " + hack.excludeClassPath);
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                //此时是配置完成了，可以拿到配置信息
                HackExtension hack = project.getExtensions().getByType(HackExtension.class);
                boolean debugOpen = hack.debugOpen;
                //debug阶段是否开启
                //拿到android{}的配置
                AppExtension appExtension = project.getExtensions().getByType(AppExtension.class);
                // android项目默认会有 debug和release，
                // 那么getApplicationVariants就是包含了debug和release的集合，all表示对集合进行遍历
                appExtension.getApplicationVariants().all(new Action<ApplicationVariant>() {
                    @Override
                    public void execute(ApplicationVariant applicationVariant) {
                        String variantName = applicationVariant.getName();
                        if (variantName.contains("debug") && !debugOpen) {
                            System.out.println("rzm debug模式不处理");
                            //debug模式下不插桩
                            return;
                        }
                        //首字母大些
                        variantName = Utils.capitalize(variantName);
                        //gradle 4.4
                        Task task = project.getTasks().getByName("transformClassesWithDexBuilderFor" + variantName);
                        System.out.println("rzm task = " + task);

                        task.doFirst(new Action<Task>() {
                            @Override
                            public void execute(Task task) {
                                //任务的输入，dex打包任务要输入什么？ 自然是所有的class与jar包了！
                                FileCollection files = task.getInputs().getFiles();

                                for (File file : files) {
                                    String absolutePath = file.getAbsolutePath();
                                    /**
                                     * rzm class = /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/app/build/intermediates/transforms/desugar/debug/0/com/rzm/usingofgradleplugin/R$attr.class
                                     * rzm class = /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/app/build/intermediates/transforms/desugar/debug/0/com/rzm/usingofgradleplugin/R$drawable.class
                                     * rzm class = /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/app/build/intermediates/transforms/desugar/debug/14.jar
                                     * rzm class = /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/app/build/intermediates/transforms/desugar/debug/28.jar
                                     */
                                    System.out.println("rzm" + " class = " + absolutePath);
                                    if (absolutePath.endsWith(".jar")) {
//                                        processJar(file);
                                    }
                                    if (absolutePath.endsWith(".class")) {
                                        processClass(applicationVariant.getDirName(), file);
                                    }
                                }
                            }
                        });
                    }
                });
            }
        });

    }

    /**
     * @param dirName debug
     * @param file    /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/app/build/intermediates/transforms/desugar/debug/0/com/rzm/usingofgradleplugin/R$drawable.class
     */
    private void processClass(String dirName, File file) {
        String filePath = file.getAbsolutePath();
        //注意这里的filePath包含了目录+包名+类名，所以去掉目录
        String className = filePath.split(dirName)[1].substring(3);
        System.out.println("rzm" + "className = " + file.getAbsolutePath());
        //android support不处理
        if (isAndroidClass(className)) {
            return;
        }
        try {
            // byte[]->class 修改byte[]
            FileInputStream is = new FileInputStream(filePath);
            //执行插桩  byteCode:插桩之后的class数据，把他替换掉插桩前的class文件
            byte[] byteCode = hackClass(is);
            is.close();

            FileOutputStream os = new FileOutputStream(filePath);
            os.write(byteCode);
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processJar(File file) {
        try {
            File bakJar = new File(file.getParent(), file.getName() + ".bak");
            JarOutputStream jos = new JarOutputStream(new FileOutputStream(bakJar));
            JarFile jarFile = new JarFile(file);
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                String className = jarEntry.getName();
                jos.putNextEntry(new JarEntry(className));
                System.out.println("rzm" + "processJar class = " + className);
                InputStream inputStream = jarFile.getInputStream(jarEntry);
                if (className.endsWith(".class") && !isAndroidClass(className)) {
                    byte[] byteCode = hackClass(inputStream);
                    jos.write(byteCode);
                } else {
                    jos.write(IOUtils.toByteArray(inputStream));
                }
                jos.closeEntry();
            }
            jos.close();
            jarFile.close();
            file.delete();
            bakJar.renameTo(file);
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    private byte[] hackClass(InputStream inputStream) {
        try {
            ClassReader reader = new ClassReader(inputStream);
            ClassWriter writer = new ClassWriter(reader, 0);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM5, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    System.out.println("rzm" + " method name = " + name);
                    MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);
                    MyMethodVisitor myMethodVisitor = new MyMethodVisitor(Opcodes.ASM5, methodVisitor, access, name, desc);
                    return myMethodVisitor;
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new byte[1];
    }


    /**
     * public class ClassHack {
     *     public ClassHack() {
     *     }
     *
     *     public static void test(String scene) {
     *         long start = System.currentTimeMillis();
     *         long end = System.currentTimeMillis();
     *         System.out.println("consume time:" + (end - start));
     *     }
     * }
     *
     * 得到字节码（把本次无关的删了）
     *
     * renzhenming@renzhenmingdeMacBook-Air main % javap -verbose /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/buildSrc/build/classes/java/main/ClassHack.class
     * Classfile /Users/renzhenming/AndroidStudioProjects/UsingOfGradlePlugin/buildSrc/build/classes/java/main/ClassHack.class
     *   Last modified 2023-4-16; size 802 bytes
     *   MD5 checksum 4e4325728f6c4fdfe647d54c9012c67a
     *   Compiled from "ClassHack.java"
     * public class ClassHack
     *   minor version: 0
     *   major version: 52
     *   flags: ACC_PUBLIC, ACC_SUPER
     * {
     *   public ClassHack();
     *     descriptor: ()V
     *     flags: ACC_PUBLIC
     *     Code:
     *       stack=1, locals=1, args_size=1
     *          0: aload_0
     *          1: invokespecial #1                  // Method java/lang/Object."<init>":()V
     *          4: return
     *       LineNumberTable:
     *         line 1: 0
     *       LocalVariableTable:
     *         Start  Length  Slot  Name   Signature
     *             0       5     0  this   LClassHack;
     *
     *   public static void test(java.lang.String);
     *     descriptor: (Ljava/lang/String;)V
     *     flags: ACC_PUBLIC, ACC_STATIC
     *     Code:
     *       stack=6, locals=5, args_size=1
     *          0: invokestatic  #2                  // Method java/lang/System.currentTimeMillis:()J
     *          3: lstore_1
     *          4: invokestatic  #2                  // Method java/lang/System.currentTimeMillis:()J
     *          7: lstore_3
     *
     *         8: getstatic     #3                  // Field java/lang/System.out:Ljava/io/PrintStream;
     *         11: new           #4                  // class java/lang/StringBuilder
     *         14: dup
     *         15: invokespecial #5                  // Method java/lang/StringBuilder."<init>":()V
     *         18: ldc           #6                  // String consume time:
     *         20: invokevirtual #7                  // Method java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;
     *         23: lload_3
     *         24: lload_1
     *         25: lsub
     *         26: invokevirtual #8                  // Method java/lang/StringBuilder.append:(J)Ljava/lang/StringBuilder;
     *         29: invokevirtual #9                  // Method java/lang/StringBuilder.toString:()Ljava/lang/String;
     *         32: invokevirtual #10                 // Method java/io/PrintStream.println:(Ljava/lang/String;)V
     *         35: return
     * }
     * SourceFile: "ClassHack.java"
     */
    static class MyMethodVisitor extends AdviceAdapter {

        private int start;

        /**
         * Creates a new {@link AdviceAdapter}.
         *
         * @param api    the ASM API version implemented by this visitor. Must be one
         *               of {@link Opcodes#ASM4} or {@link Opcodes#ASM5}.
         * @param mv     the method visitor to which this adapter delegates calls.
         * @param access the method's access flags (see {@link Opcodes}).
         * @param name   the method's name.
         * @param desc   the method's descriptor (see {@link Type Type}).
         */
        protected MyMethodVisitor(int api, MethodVisitor mv, int access, String name, String desc) {
            super(api, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            super.onMethodEnter();
            //插入这一句
            //long start = System.currentTimeMillis();
            invokeStatic(Type.getType("Ljava/lang/System;"), new Method("currentTimeMillis", "()J"));
            start = newLocal(Type.LONG_TYPE);
            storeLocal(start);
        }

        @Override
        protected void onMethodExit(int opcode) {
            super.onMethodExit(opcode);
            //插入这一句
            //long end = System.currentTimeMillis();
//            System.out.println("consume time:" + (end - start));

            invokeStatic(Type.getType("Ljava/lang/System;"), new Method("currentTimeMillis", "()J"));
            int end = newLocal(Type.LONG_TYPE);
            storeLocal(end);

            getStatic(Type.getType("Ljava/lang/System;"), "out", Type.getType("Ljava/io/PrintStream;"));

            newInstance(Type.getType("Ljava/lang/StringBuilder;"));
            dup();

            invokeConstructor(Type.getType("Ljava/lang/StringBuilder;"), new Method("<init>", "()V"));

            visitLdcInsn("consume time:");

            invokeVirtual(Type.getType("Ljava/lang/StringBuilder;"), new Method("append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));

            loadLocal(end);

            loadLocal(start);
            math(SUB, Type.LONG_TYPE);

            invokeVirtual(Type.getType("Ljava/lang/StringBuilder;"), new Method("append", "(J)Ljava/lang/StringBuilder;"));
            invokeVirtual(Type.getType("Ljava/lang/StringBuilder;"), new Method("toString", "()Ljava/lang/String;"));
            invokeVirtual(Type.getType("Ljava/io/PrintStream;"), new Method("println", "(Ljava/lang/String;)V"));
        }
    }

    static boolean isAndroidClass(String filePath) {
        return filePath.startsWith("android") ||
                filePath.startsWith("androidx");
    }
}
