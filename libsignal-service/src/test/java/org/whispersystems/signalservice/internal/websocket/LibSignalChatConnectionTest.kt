package org.whispersystems.signalservice.internal.websocket

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.reactivex.rxjava3.observers.TestObserver
import org.junit.Before
import org.junit.Test
import org.signal.libsignal.internal.CompletableFuture
import org.signal.libsignal.net.ChatService
import org.signal.libsignal.net.ChatService.DebugInfo
import org.signal.libsignal.net.IpType
import org.signal.libsignal.net.Network
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.signal.libsignal.net.ChatService.Response as LibSignalResponse
import org.signal.libsignal.net.ChatService.ResponseAndDebugInfo as LibSignalDebugResponse

class LibSignalChatConnectionTest {

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val healthMonitor = mockk<HealthMonitor>()
  private val chatService = mockk<ChatService>()
  private val network = mockk<Network>()
  private val connection = LibSignalChatConnection("test", network, null, false, healthMonitor)

  @Before
  fun before() {
    clearAllMocks()
    mockkStatic(Network::createChatService)
    every { healthMonitor.onMessageError(any(), any()) }
    every { healthMonitor.onKeepAliveResponse(any(), any(), any()) }
    every { network.createChatService(any(), any()) } answers { chatService }
  }

  @Test
  fun orderOfStatesOnSuccessfulConnect() {
    val latch = CountDownLatch(1)

    every { chatService.connect() } answers {
      delay {
        it.complete(DEBUG_INFO)
        latch.countDown()
      }
    }

    val observer = TestObserver<WebSocketConnectionState>()
    connection.state.subscribe(observer)

    connection.connect()

    latch.await(100, TimeUnit.MILLISECONDS)

    observer.assertNotComplete()
    observer.assertValues(
      WebSocketConnectionState.DISCONNECTED,
      WebSocketConnectionState.CONNECTING,
      WebSocketConnectionState.CONNECTED
    )
  }

  @Test
  fun orderOfStatesOnConnectionFailure() {
    val connectionException = RuntimeException("connect failed")
    val latch = CountDownLatch(1)

    every { chatService.connect() } answers {
      delay {
        it.completeExceptionally(connectionException)
      }
    }

    val observer = TestObserver<WebSocketConnectionState>()
    connection.state.subscribe(observer)

    connection.connect()

    latch.await(100, TimeUnit.MILLISECONDS)

    observer.assertNotComplete()
    observer.assertValues(
      WebSocketConnectionState.DISCONNECTED,
      WebSocketConnectionState.CONNECTING,
      WebSocketConnectionState.FAILED
    )
  }

  @Test
  fun orderOfStatesOnConnectAndDisconnect() {
    val connectLatch = CountDownLatch(1)
    val disconnectLatch = CountDownLatch(1)

    every { chatService.connect() } answers {
      delay {
        it.complete(DEBUG_INFO)
        connectLatch.countDown()
      }
    }
    every { chatService.disconnect() } answers {
      delay {
        it.complete(null)
        disconnectLatch.countDown()
      }
    }

    val observer = TestObserver<WebSocketConnectionState>()

    connection.state.subscribe(observer)

    connection.connect()
    connectLatch.await(100, TimeUnit.MILLISECONDS)
    connection.disconnect()
    disconnectLatch.await(100, TimeUnit.MILLISECONDS)

    observer.assertNotComplete()
    observer.assertValues(
      WebSocketConnectionState.DISCONNECTED,
      WebSocketConnectionState.CONNECTING,
      WebSocketConnectionState.CONNECTED,
      WebSocketConnectionState.DISCONNECTING,
      WebSocketConnectionState.DISCONNECTED
    )
  }

  @Test
  fun orderOfStatesOnDisconnectFailure() {
    val disconnectException = RuntimeException("disconnect failed")

    val connectLatch = CountDownLatch(1)
    val disconnectLatch = CountDownLatch(1)

    every { chatService.disconnect() } answers {
      delay {
        it.completeExceptionally(disconnectException)
        disconnectLatch.countDown()
      }
    }

    every { chatService.connect() } answers {
      delay {
        it.complete(DEBUG_INFO)
        connectLatch.countDown()
      }
    }

    connection.connect()

    connectLatch.await(100, TimeUnit.MILLISECONDS)

    val observer = TestObserver<WebSocketConnectionState>()
    connection.state.subscribe(observer)

    connection.disconnect()

    disconnectLatch.await(100, TimeUnit.MILLISECONDS)

    observer.assertNotComplete()
    observer.assertValues(
      WebSocketConnectionState.CONNECTED,
      WebSocketConnectionState.DISCONNECTING,
      WebSocketConnectionState.DISCONNECTED
    )
  }

  @Test
  fun keepAliveSuccess() {
    val latch = CountDownLatch(1)

    every { chatService.sendAndDebug(any()) } answers {
      delay {
        it.complete(make_debug_response(RESPONSE_SUCCESS))
        latch.countDown()
      }
    }

    every { chatService.connect() } answers {
      delay {
        it.complete(DEBUG_INFO)
      }
    }

    connection.connect()

    connection.sendKeepAlive()

    latch.await(100, TimeUnit.MILLISECONDS)

    verify(exactly = 1) {
      healthMonitor.onKeepAliveResponse(any(), false, any())
    }
    verify(exactly = 0) {
      healthMonitor.onMessageError(any(), any())
    }
  }

  @Test
  fun keepAliveFailure() {
    for (response in listOf(RESPONSE_ERROR, RESPONSE_SERVER_ERROR)) {
      val latch = CountDownLatch(1)

      every { chatService.sendAndDebug(any()) } answers {
        delay {
          it.complete(make_debug_response(response))
        }
      }

      every { chatService.connect() } answers {
        delay {
          it.complete(DEBUG_INFO)
        }
      }

      connection.connect()

      connection.sendKeepAlive()
      latch.await(100, TimeUnit.MILLISECONDS)

      verify(exactly = 1) {
        healthMonitor.onMessageError(response.status, false)
      }
      verify(exactly = 0) {
        healthMonitor.onKeepAliveResponse(any(), any(), any())
      }
    }
  }

  @Test
  fun keepAliveConnectionFailure() {
    val connectionFailure = RuntimeException("Sending keep-alive failed")

    val connectLatch = CountDownLatch(1)
    val keepAliveFailureLatch = CountDownLatch(1)

    every {
      chatService.sendAndDebug(any())
    } answers {
      delay {
        it.completeExceptionally(connectionFailure)
        keepAliveFailureLatch.countDown()
      }
    }

    every { chatService.connect() } answers {
      delay {
        it.complete(DEBUG_INFO)
        connectLatch.countDown()
      }
    }

    connection.connect()
    connectLatch.await(100, TimeUnit.MILLISECONDS)

    val observer = TestObserver<WebSocketConnectionState>()
    connection.state.subscribe(observer)

    connection.sendKeepAlive()

    keepAliveFailureLatch.await(100, TimeUnit.MILLISECONDS)

    observer.assertNotComplete()
    observer.assertValues(
      // We start in the connected state
      WebSocketConnectionState.CONNECTED,
      // Disconnects as a result of keep-alive failure
      WebSocketConnectionState.DISCONNECTED
    )
    verify(exactly = 0) {
      healthMonitor.onKeepAliveResponse(any(), any(), any())
      healthMonitor.onMessageError(any(), any())
    }
  }

  private fun <T> delay(action: ((CompletableFuture<T>) -> Unit)): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    executor.submit {
      action(future)
    }
    return future
  }

  companion object {
    private val DEBUG_INFO: DebugInfo = DebugInfo(IpType.UNKNOWN, 100, "")
    private val RESPONSE_SUCCESS = LibSignalResponse(200, "", emptyMap(), byteArrayOf())
    private val RESPONSE_ERROR = LibSignalResponse(400, "", emptyMap(), byteArrayOf())
    private val RESPONSE_SERVER_ERROR = LibSignalResponse(500, "", emptyMap(), byteArrayOf())

    private fun make_debug_response(response: LibSignalResponse): LibSignalDebugResponse {
      return LibSignalDebugResponse(response, DEBUG_INFO)
    }
  }
}
