import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// 薄包部署冒烟检查: 复刻 PluginManager 的扁平 URLClassLoader (plugin-libraries/*.jar + plugins/*.jar,
// parent = app classloader), 对每个插件 jar 的全部类做 Class.forName(initialize=false):
// 加载即解析父类/接口, 共享库缺 jar 会立刻抛 NoClassDefFoundError, 不必真的把 bot 跑起来。
//
// 用法 (在部署目录, 即含 plugin-libraries/ 与 plugins/ 的目录下):
//   javac -d /tmp tools/load-check/LoadCheck.java
//   java -cp "/tmp:arona-core-<version>.jar" LoadCheck plugin-libraries plugins
public class LoadCheck {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: LoadCheck <librariesDir> <pluginsDir>");
      System.exit(2);
    }
    File libraries = new File(args[0]);
    File plugins = new File(args[1]);
    if (!plugins.isDirectory()) {
      System.err.println("not a directory: " + plugins);
      System.exit(2);
    }
    List<URL> urls = new ArrayList<>();
    List<File> pluginJars = new ArrayList<>();
    for (File dir : new File[] { libraries, plugins }) {
      File[] jars = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
      if (jars == null) continue;
      java.util.Arrays.sort(jars);
      for (File jar : jars) {
        urls.add(jar.toURI().toURL());
        if (dir == plugins) pluginJars.add(jar);
      }
    }
    URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), LoadCheck.class.getClassLoader());
    int total = 0, failed = 0;
    for (File jar : pluginJars) {
      int jarFailed = 0;
      try (JarFile jf = new JarFile(jar)) {
        for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
          String name = e.nextElement().getName();
          if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.contains("module-info")) continue;
          String cls = name.substring(0, name.length() - 6).replace('/', '.');
          total++;
          try {
            Class.forName(cls, false, loader);
          } catch (Throwable t) {
            failed++;
            if (jarFailed++ < 5) System.out.println("FAIL " + jar.getName() + " " + cls + " -> " + t);
          }
        }
      }
      System.out.println(jar.getName() + ": " + (jarFailed == 0 ? "OK" : jarFailed + " failures"));
    }
    System.out.println("total=" + total + " failed=" + failed);
    if (pluginJars.isEmpty() || total == 0) {
      System.err.println("no plugin classes checked, refusing to report success");
      System.exit(2);
    }
    if (failed > 0) System.exit(1);
  }
}
