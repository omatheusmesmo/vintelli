public class Flooder {

    public static void main(String[] args) {
        System.out.println("x".repeat(50_000));
        for (int index = 0; index < 12_000; index++) {
            System.out.println("line:" + index);
        }
    }
}
