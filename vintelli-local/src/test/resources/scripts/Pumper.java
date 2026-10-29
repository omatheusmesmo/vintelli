public class Pumper {

    public static void main(String[] args) throws InterruptedException {
        long delay = args.length == 0 ? 200L : Long.parseLong(args[0]);
        long count = 0L;
        while (count < 10_000L) {
            System.out.println("working:" + count);
            System.out.flush();
            Thread.sleep(delay);
            count++;
        }
    }
}
