package limn.backend.internal;

import limn.backend.PrintResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * How a print asked from inside a stage of its own hands the system's answer on to the stage its
 * caller holds: {@link limn.backend.Printer#print(java.nio.file.Path, String)} asks once the file
 * is read, and {@code limn.print.Document#print} once the PDF is written. What a dependent stage
 * throws ends that stage, which nobody holds, so a peer that threw (an {@link Error} included) or
 * whose stage failed left the caller's stage never completed. Not API.
 */
public final class PrintAnswers {

    private static final System.Logger LOG = System.getLogger(limn.backend.Printer.class.getName());

    private PrintAnswers() {
    }

    /**
     * Completes {@code answer} with what the system answers, and with {@link PrintResult#FAILED},
     * logged as a warning, for a call that throws or a stage that fails.
     *
     * @param ask    asks the system and returns its stage
     * @param answer the stage the caller holds
     * @param title  the job's name, for the warning
     */
    public static void answerWith(Supplier<? extends CompletionStage<PrintResult>> ask,
                                  CompletableFuture<PrintResult> answer, String title) {
        CompletionStage<PrintResult> asked;
        try {
            asked = ask.get();
        } catch (RuntimeException | Error failed) {
            answer.complete(PrintResult.FAILED);
            LOG.log(System.Logger.Level.WARNING, "not printed, the system's print dialog failed: " + title, failed);
            return;
        }
        asked.whenComplete((result, failed) -> {
            if (failed != null) {
                LOG.log(System.Logger.Level.WARNING, "not printed, the system's print dialog failed: " + title,
                        failed);
            }
            answer.complete(failed == null && result != null ? result : PrintResult.FAILED);
        });
    }
}
