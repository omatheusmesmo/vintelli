import java.util.Arrays;

public class Ping {

    public static void main(String[] args) {
        for (String arg : args) {
            System.out.println("arg:" + arg);
        }
        String mark = System.getenv("VINTELLI_LOCAL_TEST_MARK");
        System.out.println("mark:" + (mark == null ? "unset" : mark));
        System.out.println("wd:" + System.getProperty("user.dir"));
        System.err.println("stderr:ping");
        System.out.println("done");
    }
}
