import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.concurrent.CyclicBarrier;

/**
 * False Sharing Benchmark
 *
 * Each thread increments ONLY ITS OWN counter. There is no logical sharing,
 * yet when counters sit on the same 64-byte cache line the cores fight over
 * that line (MESI invalidations), and throughput collapses.
 *
 * Layouts compared (long = 8 bytes):
 *   ADJACENT : counters 1 long apart      -> 8 bytes  -> same cache line
 *   PAD_64   : counters 8 longs apart     -> 64 bytes -> one line each
 *   PAD_128  : counters 16 longs apart    -> 128 bytes -> also defeats the
 *              adjacent-line prefetcher on Intel CPUs
 *
 * Run:   java FalseSharingBenchmark.java            (JDK 11+)
 *   or:  javac FalseSharingBenchmark.java && java FalseSharingBenchmark [threads] [seconds]
 */
public class FalseSharingBenchmark {

    private static final VarHandle ELEM = MethodHandles.arrayElementVarHandle(long[].class);

    enum Layout {
        ADJACENT(1), PAD_64(8), PAD_128(16);
        final int strideLongs;
        Layout(int s) { strideLongs = s; }
    }

    /** Run all threads for `millis`; return total increments across threads. */
    static long run(Layout layout, int threads, long millis) throws Exception {
        // +2 strides of slack so thread 0's slot is not at the very start of the array header line
        long[] data = new long[(threads + 2) * layout.strideLongs + 16];
        long[] counts = new long[threads];
        CyclicBarrier start = new CyclicBarrier(threads + 1);
        Thread[] ts = new Thread[threads];
        final long[] stop = {0};

        for (int t = 0; t < threads; t++) {
            final int id = t;
            final int idx = (id + 1) * layout.strideLongs;
            ts[t] = new Thread(() -> {
                try { start.await(); } catch (Exception e) { return; }
                long n = 0;
                // Opaque access: JIT cannot hoist the store out of the loop,
                // but no expensive fence/lock prefix is added. Pure cache-line cost shows.
                while ((n & 0xFFFF) != 0 || System.nanoTime() < stop[0]) {
                    ELEM.setOpaque(data, idx, (long) ELEM.getOpaque(data, idx) + 1);
                    n++;
                }
                counts[id] = n;
            }, "worker-" + t);
            ts[t].start();
        }
        stop[0] = System.nanoTime() + millis * 1_000_000L;
        start.await();
        for (Thread t : ts) t.join();

        long total = 0;
        for (long c : counts) total += c;
        return total;
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : Math.min(4, cores);
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 3;
        int rounds = 5;

        System.out.printf("CPU cores: %d | threads: %d | %ds per run | %d rounds%n%n",
                cores, threads, seconds, rounds);
        if (cores < 2) {
            System.out.println("WARNING: only 1 core visible. False sharing needs >= 2 physical cores "
                    + "running in parallel, so results below will NOT show a difference.\n");
        }

        // Warm-up so the JIT compiles the hot loop
        for (Layout l : Layout.values()) run(l, threads, 500);

        System.out.println("layout,round,total_ops,Mops_per_sec");
        double[] avg = new double[Layout.values().length];
        for (int r = 1; r <= rounds; r++) {
            for (Layout l : Layout.values()) {
                long ops = run(l, threads, seconds * 1000);
                double mops = ops / (seconds * 1e6);
                avg[l.ordinal()] += mops / rounds;
                System.out.printf(Locale.US, "%s,%d,%d,%.1f%n", l, r, ops, mops);
            }
        }

        System.out.println("\n=== Average throughput (Mops/sec, higher is better) ===");
        for (Layout l : Layout.values()) {
            System.out.printf(Locale.US, "%-9s %10.1f   (%.2fx vs ADJACENT)%n",
                    l, avg[l.ordinal()], avg[l.ordinal()] / avg[0]);
        }
    }
}
