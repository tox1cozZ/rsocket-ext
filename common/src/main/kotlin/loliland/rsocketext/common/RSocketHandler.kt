package loliland.rsocketext.common

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.utils.io.core.*
import io.rsocket.kotlin.ConnectionAcceptorContext
import io.rsocket.kotlin.ExperimentalMetadataApi
import io.rsocket.kotlin.RSocket
import io.rsocket.kotlin.RSocketRequestHandler
import io.rsocket.kotlin.core.WellKnownMimeType
import io.rsocket.kotlin.metadata.*
import io.rsocket.kotlin.payload.Payload
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import loliland.rsocketext.common.dto.ResponseError
import loliland.rsocketext.common.exception.ResponseException
import loliland.rsocketext.common.exception.SilentCancellationException
import loliland.rsocketext.common.extensions.RequestTracker
import loliland.rsocketext.common.extensions.errorPayload
import loliland.rsocketext.common.extensions.jsonPayload
import loliland.rsocketext.common.extensions.readJson
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaType
import kotlin.reflect.typeOf

@Suppress("DuplicatedCode")
@OptIn(ExperimentalMetadataApi::class)
abstract class RSocketHandler(val mapper: ObjectMapper, val tracker: RequestTracker? = null) {

    private val routeHandlers = findHandlers<RSocketRoute>()
    private val metadataHandlers = findHandlers<RSocketMetadata>()

    init {
        validateRouteHandler()
        validateMetadataHandler()
    }

    abstract fun setupConnection(ctx: ConnectionAcceptorContext)

    fun buildSocketHandler(): RSocket {
        return RSocketRequestHandler {
            metadataPush {
                onMetadataPush(it)
            }
            fireAndForget {
                onFireAndForget(it)
            }
            requestResponse {
                onRequestResponse(it)
            }
            requestStream {
                onRequestStream(it)
            }
            requestChannel { initPayload, payloads ->
                onRequestChannel(initPayload, payloads)
            }
        }
    }

    open suspend fun onMetadataPush(metadata: ByteReadPacket): Unit = metadata.use {
        metadataHandlers.forEach { (handler) ->
            it.copy().use { packet ->
                try {
                    handler.callSuspend(this, packet)
                } catch (e: Throwable) {
                    // Propagate current coroutine cancellation
                    coroutineContext.ensureActive()

                    // TODO Use logging library
                    e.printStackTrace()
                }
            }
        }
    }

    open suspend fun onFireAndForget(request: Payload): Unit = request.use {
        var route = "<unknown>"

        try {
            val metadataPayload = it.readMetadata()
            route = metadataPayload.route()
            val handler = findHandler(route)
            val payload = handler.decodeRequestData(it)
            val metadata = handler.decodeRequestMetadata(it, metadataPayload)

            val thisRef = handler.parameters.first() to this
            val args = listOfNotNull(thisRef, payload, metadata).toMap()
            handler.callSuspendByAndUnwrapResponseException(route, args)
        } catch (_: ResponseException) {
        } catch (e: Throwable) {
            // Propagate current coroutine cancellation
            coroutineContext.ensureActive()

            // TODO Use logging library
            System.err.println("Failed to handle onFireAndForget($route) request")
            e.printStackTrace()
        }
    }

    open suspend fun onRequestResponse(request: Payload): Payload {
        return request.use {
            var route = "<unknown>"

            try {
                val metadataPayload = it.readMetadata()
                route = metadataPayload.route()
                val handler = findHandler(route)
                val payload = handler.decodeRequestData(it)
                val metadata = handler.decodeRequestMetadata(it, metadataPayload)

                val thisRef = handler.parameters.first() to this
                val args = listOfNotNull(thisRef, payload, metadata).toMap()
                when (val response = handler.callSuspendByAndUnwrapResponseException(route, args)) {
                    is Unit -> Payload.Empty
                    is Payload -> response
                    is ResponseError -> errorPayload(error = response, mapper = mapper)
                    else -> jsonPayload(data = response, mapper = mapper)
                }
            } catch (e: ResponseException) {
                errorPayload(error = e.error, mapper = mapper)
            } catch (e: Throwable) {
                // Propagate current coroutine cancellation
                coroutineContext.ensureActive()

                val message = when (e) {
                    is SilentCancellationException -> e.javaClass.name + ": " + e.message
                    else -> {
                        // TODO Use logging library
                        System.err.println("Failed to handle onRequestResponse($route) request")
                        e.printStackTrace()

                        val trace = e.stackTraceToString()
                        val traceParts = trace.split("\t").map(String::trim)
                        if (traceParts.size > 1) {
                            "${traceParts[0]} ${traceParts[1]}"
                        } else {
                            trace
                        }
                    }
                }

                errorPayload(error = ResponseError(code = message.hashCode(), message = message), mapper = mapper)
            }
        }
    }

    // TODO: Implement onRequestStream
    open suspend fun onRequestStream(request: Payload): Flow<Payload> {
        request.close()
        throw NotImplementedError("Request Channel is not implemented.")
    }

    // TODO: Implement onRequestChannel
    open suspend fun onRequestChannel(initPayload: Payload, payloads: Flow<Payload>): Flow<Payload> {
        initPayload.close()
        throw NotImplementedError("Request Channel is not implemented.")
    }

    private fun validateRouteHandler() {
        val routes = mutableSetOf<String>()
        routeHandlers.forEach { (handler, route) ->
            if (route.value in routes) {
                throw IllegalStateException("Duplicate @RSocketMetadata handler '${handler.name}' for route: ${route.value}")
            }
            routes += route.value
        }
    }

    private fun validateMetadataHandler() {
        metadataHandlers.forEach { (handler, _) ->
            if (handler.returnType != typeOf<Unit>()) {
                throw IllegalStateException("The @RSocketMetadata handler must have a return Unit type: ${handler.name}")
            }

            val params = handler.parameters
            if (params.size != 1 && params.single().type != typeOf<ByteReadPacket>()) {
                throw IllegalStateException("The @RSocketMetadata handler must have a single param with ByteReadPacket type: ${handler.name}")
            }
        }
    }

    private fun findHandler(route: String): KFunction<*> {
        return routeHandlers.firstOrNull { it.second.value == route }?.first ?: error("Route $route doesn't exists.")
    }

    private fun KFunction<*>.decodeRequestData(request: Payload): Pair<KParameter, Any>? {
        val parameter = parameters.firstOrNull { it.hasAnnotation<RSocketRoute.Payload>() } ?: return null
        return parameter to if (parameter == typeOf<Payload>()) {
            request
        } else {
            request.data.readJson<Any>(mapper, parameter.type.javaType)
        }
    }

    @OptIn(ExperimentalMetadataApi::class)
    private fun KFunction<*>.decodeRequestMetadata(
        request: Payload,
        metadataPayload: CompositeMetadata
    ): Pair<KParameter, Any>? {
        val parameter = parameters.firstOrNull { it.hasAnnotation<RSocketRoute.Metadata>() } ?: return null
        return parameter to if (parameter == typeOf<ByteReadPacket>()) {
            request
        } else {
            val metadata = metadataPayload.getOrNull(WellKnownMimeType.ApplicationJson)
                ?.read(RawMetadata.reader(WellKnownMimeType.ApplicationJson))
            checkNotNull(metadata) {
                "The $name function has a @Metadata parameter, but there is no metadata in the request!"
            }
            metadata.content.readJson<Any>(mapper, parameter.type.javaType)
        }
    }

    private fun Payload.readMetadata(): CompositeMetadata =
        metadata?.read(CompositeMetadata) ?: error("Broken metadata.")

    private fun CompositeMetadata.route(): String =
        getOrNull(WellKnownMimeType.MessageRSocketRouting)?.read(RoutingMetadata)?.tags?.firstOrNull()
            ?: error("No route specified.")

    private inline fun <reified A : Annotation> findHandlers(): List<Pair<KFunction<*>, A>> =
        this::class.functions.filter { it.hasAnnotation<A>() }.map { it to it.findAnnotation<A>()!! }

    private suspend fun <R> KCallable<R>.callSuspendByAndUnwrapResponseException(
        route: String,
        args: Map<KParameter, Any?>
    ): R {
        try {
            return if (tracker != null) {
                tracker.execute(route) { callSuspendBy(args) }
            } else {
                callSuspendBy(args)
            }
        } catch (e: InvocationTargetException) {
            val cause = e.cause

            if (cause is ResponseException) {
                throw cause
            } else {
                throw e
            }
        }
    }
}