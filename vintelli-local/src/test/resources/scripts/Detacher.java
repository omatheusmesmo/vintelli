import java.nio.file.Files;
import java.nio.file.Path;

public class Detacher {

    public static void main(String[] args) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process child = new ProcessBuilder(java, args[0], "30").inheritIO().start();
        System.out.println("child:" + child.pid());
        Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
        System.out.println("detached");
        System.out.flush();
    }
}
