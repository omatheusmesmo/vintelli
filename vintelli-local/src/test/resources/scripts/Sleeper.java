public class Sleeper {

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(Long.parseLong(args[0]) * 1000L);
    }
}
