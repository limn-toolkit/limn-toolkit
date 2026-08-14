package limn.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A {@link CompletableFuture} whose default async executor is the UI thread,
 * so {@code thenAcceptAsync}/{@code whenCompleteAsync} without an explicit
 * executor run UI-safely. Instances are completed via {@link UiRuntime#post},
 * which puts non-async dependents registered before completion on the UI
 * thread as well; see {@link UiRuntime#async} for the exact guarantee (late
 * registration from a background thread and {@code cancel()} run inline on
 * the calling thread, as per {@link CompletableFuture} semantics).
 */
final class UiFuture<T> extends CompletableFuture<T> {

    private final UiRuntime runtime;

    UiFuture(UiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Executor defaultExecutor() {
        return runtime.uiExecutor();
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new UiFuture<>(runtime);
    }
}
