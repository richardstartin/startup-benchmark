package datadog.trace.benchmark;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class LoadClasses {
    public static void main(String... args) {
        String classPath = System.getProperty("java.class.path");
        for (String path : classPath.split(File.pathSeparator)) {
            if (!path.endsWith(".jar")) {
                continue;
            }
            try (JarFile jarFile = new JarFile(path)) {
                Enumeration<JarEntry> e = jarFile.entries();
                while (e.hasMoreElements()) {
                    JarEntry jarEntry = e.nextElement();
                    String name = jarEntry.getName();
                    if (name.endsWith(".class") && !name.startsWith("BOOT-INF")) {
                        name = name.replace('/', '.');
                        name = name.substring(0, name.length() - ".class".length());
                        try {
                            Class.forName(name, false, LoadClasses.class.getClassLoader());
                        } catch (NoClassDefFoundError | ClassNotFoundException failure) {
                            System.err.println("couldn't load " + name + "(" + failure.getClass().getSimpleName() + ")");
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
