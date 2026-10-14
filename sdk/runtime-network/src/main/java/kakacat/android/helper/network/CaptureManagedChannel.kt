package kakacat.android.helper.network

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import java.util.concurrent.TimeUnit

/**
 * 把拦截器挂到一个已经建好的 `ManagedChannel` 上。
 *
 * 手工接入时我们是在 builder 上 `.intercept()`，不需要这个类。但插桩是在
 * `build()` 的**返回值**上动手，而 `ClientInterceptors.intercept()` 返回的是 `Channel`，
 * 类型对不上 —— 调用点期待的是 `ManagedChannel`，塞回去会 ClassCastException。
 *
 * 所以这里做一个转发壳：`newCall` 走拦截过的通道，其余生命周期方法全部原样转发给
 * delegate。`shutdown()` 必须返回 `this` 而不是 delegate，否则业务拿到的对象会
 * 悄悄"脱壳"，后续调用就绕过采集了。
 */
internal class CaptureManagedChannel(
    private val delegate: ManagedChannel,
    interceptor: ClientInterceptor,
) : ManagedChannel() {

    private val intercepted = ClientInterceptors.intercept(delegate, interceptor)

    override fun <ReqT, RespT> newCall(
        methodDescriptor: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
    ): ClientCall<ReqT, RespT> = intercepted.newCall(methodDescriptor, callOptions)

    override fun authority(): String = delegate.authority()

    override fun shutdown(): ManagedChannel {
        delegate.shutdown()
        return this
    }

    override fun shutdownNow(): ManagedChannel {
        delegate.shutdownNow()
        return this
    }

    override fun isShutdown(): Boolean = delegate.isShutdown

    override fun isTerminated(): Boolean = delegate.isTerminated

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)

    override fun getState(requestConnection: Boolean): ConnectivityState =
        delegate.getState(requestConnection)

    override fun notifyWhenStateChanged(source: ConnectivityState, callback: Runnable) =
        delegate.notifyWhenStateChanged(source, callback)

    override fun resetConnectBackoff() = delegate.resetConnectBackoff()

    override fun enterIdle() = delegate.enterIdle()

    override fun toString(): String = "CaptureManagedChannel($delegate)"
}
