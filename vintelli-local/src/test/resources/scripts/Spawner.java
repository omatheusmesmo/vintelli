public class Spawner {

    public static void main(String[] args) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        System.out.println("spawner:" + ProcessHandle.current().pid());
        System.out.flush();
        Process child = new ProcessBuilder(java, args[0]).redirectErrorStream(true).start();
        System.out.println("child:" + child.pid());
        System.out.flush();
        Thread.sleep(600_000L);
    }
}
