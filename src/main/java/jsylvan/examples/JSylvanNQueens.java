package jsylvan.examples;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jsylvan.JSylvan;

public final class JSylvanNQueens {

    private JSylvanNQueens() {}

    private static final class Config {
        int workers = 0; // 0 -> Sylvan autodetects cores
        long memoryMb = 512;
        int granularity = 3;
        final List<Integer> sizes = new ArrayList<>();
    }

    private static final class RunResult {
        final double solutions;
        final long nodesCreated;

        RunResult(double solutions, long nodesCreated) {
            this.solutions = solutions;
            this.nodesCreated = nodesCreated;
        }
    }

    public static void main(String[] args) throws IOException {
        Config cfg = parseArgs(args);
        if (cfg.sizes.isEmpty()) {
            printUsage();
            throw new IllegalArgumentException("At least one board size is required");
        }

        JSylvan.init(cfg.workers, cfg.memoryMb * 1024L * 1024L, 1, 4, cfg.granularity);
        JSylvan.disableGC();
        JSylvan.enableGC();

        try {
            for (int size : cfg.sizes) {
                RunResult result = solve(size);
                System.out.printf("NQUEENS_METRICS n=%d solutions=%.0f nodes=%d%n",
                        size, result.solutions, result.nodesCreated);
            }
        } finally {
            JSylvan.quit();
        }
    }

    private static Config parseArgs(String[] args) {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-w":
                case "--workers":
                    cfg.workers = Integer.parseInt(nextArg(args, ++i, "--workers"));
                    break;
                case "--memory-mb":
                    cfg.memoryMb = Long.parseLong(nextArg(args, ++i, "--memory-mb"));
                    break;
                case "--granularity":
                    cfg.granularity = Integer.parseInt(nextArg(args, ++i, "--granularity"));
                    break;
                case "-h":
                case "--help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    cfg.sizes.add(Integer.parseInt(args[i]));
            }
        }
        return cfg;
    }

    private static String nextArg(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("Usage: JSylvanNQueens [options] <N> [<N> ...]");
        System.out.println("Options:");
        System.out.println("  -w, --workers <n>      Number of worker threads (0 = autodetect, default: 0)");
        System.out.println("      --memory-mb <mb>   Memory budget for Sylvan tables (default: 512)");
        System.out.println("      --granularity <g>  Cache granularity (default: 3)");
        System.out.println("  -h, --help             Show this message");
    }

    private static RunResult solve(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("N must be positive");
        }

        long nodesBefore = JSylvan.getNodesCreated();
        long[][] board = allocateBoard(size);
        long queen = JSylvan.ref(JSylvan.getTrue());

        for (int row = 0; row < size; row++) {
            long clause = JSylvan.ref(JSylvan.getFalse());
            for (int col = 0; col < size; col++) {
                long tmp = JSylvan.ref(JSylvan.makeOr(clause, board[row][col]));
                JSylvan.deref(clause);
                clause = tmp;
            }
            queen = andWith(queen, clause);
        }

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                long a = JSylvan.ref(JSylvan.getTrue());
                long b = JSylvan.ref(JSylvan.getTrue());
                long c = JSylvan.ref(JSylvan.getTrue());
                long d = JSylvan.ref(JSylvan.getTrue());

                long target = board[i][j];

                for (int l = 0; l < size; l++) {
                    if (l == j) continue;
                    long clause = impliesNoConflict(target, board[i][l]);
                    a = andWith(a, clause);
                }

                for (int k = 0; k < size; k++) {
                    if (k == i) continue;
                    long clause = impliesNoConflict(target, board[k][j]);
                    b = andWith(b, clause);
                }

                for (int k = 0; k < size; k++) {
                    int ll = k - i + j;
                    if (ll >= 0 && ll < size && k != i) {
                        long clause = impliesNoConflict(target, board[k][ll]);
                        c = andWith(c, clause);
                    }
                }

                for (int k = 0; k < size; k++) {
                    int ll = i + j - k;
                    if (ll >= 0 && ll < size && k != i) {
                        long clause = impliesNoConflict(target, board[k][ll]);
                        d = andWith(d, clause);
                    }
                }

                long cd = andWith(c, d);
                long bc = andWith(b, cd);
                long ab = andWith(a, bc);
                queen = andWith(queen, ab);
            }
        }

        int[] variables = new int[size * size];
        for (int i = 0; i < variables.length; i++) {
            variables[i] = i;
        }
        long varSet = JSylvan.ref(JSylvan.makeSet(variables));
        double solutions = JSylvan.satcount(queen, varSet);
        JSylvan.deref(varSet);

        JSylvan.deref(queen);
        releaseBoard(board);

        long nodesAfter = JSylvan.getNodesCreated();
        return new RunResult(solutions, nodesAfter - nodesBefore);
    }

    private static long[][] allocateBoard(int size) {
        long[][] board = new long[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                board[row][col] = JSylvan.ref(JSylvan.makeVar(row * size + col));
            }
        }
        return board;
    }

    private static void releaseBoard(long[][] board) {
        for (long[] rows : board) {
            for (long cell : rows) {
                JSylvan.deref(cell);
            }
        }
    }

    private static long impliesNoConflict(long a, long b) {
        long notA = JSylvan.ref(JSylvan.makeNot(a));
        long notB = JSylvan.ref(JSylvan.makeNot(b));
        long clause = JSylvan.ref(JSylvan.makeOr(notA, notB));
        JSylvan.deref(notA);
        JSylvan.deref(notB);
        return clause;
    }

    private static long andWith(long lhs, long rhs) {
        long result = JSylvan.ref(JSylvan.makeAnd(lhs, rhs));
        JSylvan.deref(lhs);
        JSylvan.deref(rhs);
        return result;
    }
}
