package androidx.work;

/** Harness stand-in: records the network type asked for. */
public final class Constraints {
    public final NetworkType network;

    Constraints(NetworkType network) {
        this.network = network;
    }

    public static final class Builder {
        private NetworkType network = NetworkType.NOT_REQUIRED;

        public Builder setRequiredNetworkType(NetworkType type) {
            network = type;
            return this;
        }

        public Constraints build() {
            return new Constraints(network);
        }
    }
}
