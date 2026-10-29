package dev.omatheusmesmo.vintelli;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class VintelliLauncher {

    public static void main(String[] args) {
        Quarkus.run(VintelliMain.class, args);
    }
}
