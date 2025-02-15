/*
 * Copyright 2018 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Factory;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.services.HealthCheckingLoadBalancerFactory.SubchannelImpl;
import io.grpc.stub.StreamObserver;
import java.net.SocketAddress;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

/** Tests for {@link HealthCheckingLoadBalancerFactory}. */
@RunWith(JUnit4.class)
public class HealthCheckingLoadBalancerFactoryTest {
  private static final Attributes.Key<String> SUBCHANNEL_ATTR_KEY =
      Attributes.Key.create("subchannel-attr-for-test");

  // We use in-process channels for Subchannel.asChannel(), so that we make sure we are making RPCs
  // correctly.  Mocking Channel and ClientCall is a bad idea because it can easily be done wrong.
  // Each Channel goes to a different server, so that we can verify the health check activity on
  // each Subchannel.
  private static final int NUM_SUBCHANNELS = 2;
  private final EquivalentAddressGroup[] eags = new EquivalentAddressGroup[NUM_SUBCHANNELS];
  @SuppressWarnings({"rawtypes", "unchecked"})
  private final List<EquivalentAddressGroup>[] eagLists = new List[NUM_SUBCHANNELS];
  private final SubchannelStateListener[] mockStateListeners =
      new SubchannelStateListener[NUM_SUBCHANNELS];
  private List<EquivalentAddressGroup> resolvedAddressList;
  private final FakeSubchannel[] subchannels = new FakeSubchannel[NUM_SUBCHANNELS];
  private final ManagedChannel[] channels = new ManagedChannel[NUM_SUBCHANNELS];
  private final Server[] servers = new Server[NUM_SUBCHANNELS];
  private final HealthImpl[] healthImpls = new HealthImpl[NUM_SUBCHANNELS];

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock clock = new FakeClock();
  private final Helper origHelper = mock(Helper.class, delegatesTo(new FakeHelper()));
  // The helper seen by the origLb
  private Helper wrappedHelper;
  private final Factory origLbFactory =
      mock(Factory.class, delegatesTo(new Factory() {
          @Override
          public LoadBalancer newLoadBalancer(Helper helper) {
            checkState(wrappedHelper == null, "LoadBalancer already created");
            wrappedHelper = helper;
            return origLb;
          }
        }));

  @Mock
  private LoadBalancer origLb;
  private LoadBalancer hcLb;
  @Captor
  ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;

  private HealthCheckingLoadBalancerFactory hcLbFactory;
  private LoadBalancer hcLbEventDelivery;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      HealthImpl healthImpl = new HealthImpl();
      healthImpls[i] = healthImpl;
      Server server =
          InProcessServerBuilder.forName("health-check-test-" + i)
          .addService(healthImpl).directExecutor().build().start();
      servers[i] = server;
      ManagedChannel channel =
          InProcessChannelBuilder.forName("health-check-test-" + i).directExecutor().build();
      channels[i] = channel;

      EquivalentAddressGroup eag =
          new EquivalentAddressGroup(new FakeSocketAddress("address-" + i));
      eags[i] = eag;
      List<EquivalentAddressGroup> eagList = Arrays.asList(eag);
      eagLists[i] = eagList;
      mockStateListeners[i] = mock(SubchannelStateListener.class);
    }
    resolvedAddressList = Arrays.asList(eags);

    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(11L, 21L, 31L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(12L, 22L, 32L);

    hcLbFactory = new HealthCheckingLoadBalancerFactory(
        origLbFactory, backoffPolicyProvider,
        clock.getStopwatchSupplier());
    hcLb = hcLbFactory.newLoadBalancer(origHelper);
    // Make sure all calls into the hcLb is from the syncContext
    hcLbEventDelivery = new LoadBalancer() {
        // Per LoadBalancer API, no more callbacks will be called after shutdown() is called.
        boolean shutdown;

        @Override
        public void handleResolvedAddresses(final ResolvedAddresses resolvedAddresses) {
          syncContext.execute(new Runnable() {
              @Override
              public void run() {
                if (!shutdown) {
                  hcLb.handleResolvedAddresses(resolvedAddresses);
                }
              }
            });
        }

        @Override
        public void handleNameResolutionError(Status error) {
          throw new AssertionError("Not supposed to be called");
        }

        @Override
        public void shutdown() {
          syncContext.execute(new Runnable() {
              @Override
              public void run() {
                if (!shutdown) {
                  shutdown = true;
                  hcLb.shutdown();
                }
              }
            });
        }
      };
    verify(origLbFactory).newLoadBalancer(any(Helper.class));
  }

  @After
  public void teardown() throws Exception {
    // All scheduled tasks have been accounted for
    assertThat(clock.getPendingTasks()).isEmpty();
    // Health-check streams are usually not closed in the tests because onSubchannelState() is
    // faked.  Force closing for clean up.
    for (Server server : servers) {
      server.shutdownNow();
      assertThat(server.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }
    for (ManagedChannel channel : channels) {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }
    for (HealthImpl impl : healthImpls) {
      assertThat(impl.checkCalled).isFalse();
    }
  }

  @Test
  public void typicalWorkflow() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("FooService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);

    verify(origLb).handleResolvedAddresses(result);
    verify(origHelper, atLeast(0)).getSynchronizationContext();
    verify(origHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(origHelper);
    verifyNoMoreInteractions(origLb);
    Subchannel[] wrappedSubchannels = new Subchannel[NUM_SUBCHANNELS];

    // Simulate that the orignal LB creates Subchannels
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      // Subchannel attributes set by origLb are correctly plumbed in
      String subchannelAttrValue = "eag attr " + i;
      Attributes attrs = Attributes.newBuilder()
          .set(SUBCHANNEL_ATTR_KEY, subchannelAttrValue).build();
      wrappedSubchannels[i] = createSubchannel(i, attrs);
      assertThat(unwrap(wrappedSubchannels[i])).isSameInstanceAs(subchannels[i]);
      verify(origHelper, times(i + 1)).createSubchannel(createArgsCaptor.capture());
      assertThat(createArgsCaptor.getValue().getAddresses()).isEqualTo(eagLists[i]);
      assertThat(createArgsCaptor.getValue().getAttributes().get(SUBCHANNEL_ATTR_KEY))
          .isEqualTo(subchannelAttrValue);
    }

    for (int i = NUM_SUBCHANNELS - 1; i >= 0; i--) {
      // Not starting health check until underlying Subchannel is READY
      FakeSubchannel subchannel = subchannels[i];
      HealthImpl healthImpl = healthImpls[i];
      SubchannelStateListener mockStateListener = mockStateListeners[i];
      InOrder inOrder = inOrder(mockStateListener);
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(CONNECTING));
      deliverSubchannelState(i, ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(IDLE));

      inOrder.verify(mockStateListener).onSubchannelState(
          eq(ConnectivityStateInfo.forNonError(CONNECTING)));
      inOrder.verify(mockStateListener).onSubchannelState(
          eq(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE)));
      inOrder.verify(mockStateListener).onSubchannelState(
          eq(ConnectivityStateInfo.forNonError(IDLE)));
      verifyNoMoreInteractions(mockStateListener);

      assertThat(subchannel.logs).isEmpty();
      assertThat(healthImpl.calls).isEmpty();
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(READY));
      assertThat(healthImpl.calls).hasSize(1);
      ServerSideCall serverCall = healthImpl.calls.peek();
      assertThat(serverCall.request).isEqualTo(makeRequest("FooService"));

      // Starting the health check will make the Subchannel appear CONNECTING to the origLb.
      inOrder.verify(mockStateListener).onSubchannelState(
          eq(ConnectivityStateInfo.forNonError(CONNECTING)));
      verifyNoMoreInteractions(mockStateListener);

      assertThat(subchannel.logs).containsExactly(
          "INFO: CONNECTING: Starting health-check for \"FooService\"");
      subchannel.logs.clear();

      // Simulate a series of responses.
      for (ServingStatus servingStatus :
               new ServingStatus[] {
                 ServingStatus.UNKNOWN, ServingStatus.NOT_SERVING, ServingStatus.SERVICE_UNKNOWN,
                 ServingStatus.SERVING, ServingStatus.NOT_SERVING, ServingStatus.SERVING}) {
        serverCall.responseObserver.onNext(makeResponse(servingStatus));
        // SERVING is mapped to READY, while other statuses are mapped to TRANSIENT_FAILURE
        if (servingStatus == ServingStatus.SERVING) {
          inOrder.verify(mockStateListener).onSubchannelState(
              eq(ConnectivityStateInfo.forNonError(READY)));
          assertThat(subchannel.logs).containsExactly(
              "INFO: READY: health-check responded SERVING");
        } else {
          inOrder.verify(mockStateListener).onSubchannelState(
              unavailableStateWithMsg(
                  "Health-check service responded " + servingStatus + " for 'FooService'"));
          assertThat(subchannel.logs).containsExactly(
              "INFO: TRANSIENT_FAILURE: health-check responded " + servingStatus);
        }
        subchannel.logs.clear();
        verifyNoMoreInteractions(mockStateListener);
      }
    }

    // origLb shuts down Subchannels
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      FakeSubchannel subchannel = subchannels[i];
      SubchannelStateListener mockStateListener = mockStateListeners[i];

      ServerSideCall serverCall = healthImpls[i].calls.peek();
      assertThat(serverCall.cancelled).isFalse();
      verifyNoMoreInteractions(mockStateListener);

      assertThat(subchannels[i].isShutdown).isFalse();
      final Subchannel wrappedSubchannel = wrappedSubchannels[i];
      // Subchannel enters SHUTDOWN state as a response to shutdown(), and that will cancel the
      // health check RPC
      syncContext.execute(new Runnable() {
          @Override
          public void run() {
            wrappedSubchannel.shutdown();
          }
        });
      assertThat(subchannels[i].isShutdown).isTrue();
      assertThat(serverCall.cancelled).isTrue();
      verify(mockStateListener).onSubchannelState(
          eq(ConnectivityStateInfo.forNonError(SHUTDOWN)));
      assertThat(subchannel.logs).isEmpty();
    }

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      assertThat(healthImpls[i].calls).hasSize(1);
    }

    verifyZeroInteractions(backoffPolicyProvider);
  }

  @Test
  public void healthCheckDisabledWhenServiceNotImplemented() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("BarService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);

    verify(origLb).handleResolvedAddresses(result);
    verifyNoMoreInteractions(origLb);

    // We create 2 Subchannels. One of them connects to a server that doesn't implement health check
    for (int i = 0; i < 2; i++) {
      createSubchannel(i, Attributes.EMPTY);
    }

    InOrder inOrder = inOrder(mockStateListeners[0], mockStateListeners[1]);

    for (int i = 0; i < 2; i++) {
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(READY));
      assertThat(healthImpls[i].calls).hasSize(1);
      inOrder.verify(mockStateListeners[i]).onSubchannelState(
          eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    }

    ServerSideCall serverCall0 = healthImpls[0].calls.poll();
    ServerSideCall serverCall1 = healthImpls[1].calls.poll();

    subchannels[0].logs.clear();
    // subchannels[0] gets UNIMPLEMENTED for health checking, which will disable health
    // checking and it'll use the original state, which is currently READY.
    // In reality UNIMPLEMENTED is generated by GRPC server library, but the client can't tell
    // whether it's the server library or the service implementation that returned this status.
    serverCall0.responseObserver.onError(Status.UNIMPLEMENTED.asException());
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));
    assertThat(subchannels[0].logs).containsExactly(
        "ERROR: Health-check disabled: " + Status.UNIMPLEMENTED,
        "INFO: READY (no health-check)").inOrder();

    // subchannels[1] has normal health checking
    serverCall1.responseObserver.onNext(makeResponse(ServingStatus.NOT_SERVING));
    inOrder.verify(mockStateListeners[1]).onSubchannelState(
        unavailableStateWithMsg("Health-check service responded NOT_SERVING for 'BarService'"));

    // Without health checking, states from underlying Subchannel are delivered directly to the mock
    // listeners.
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(IDLE)));

    // Re-connecting on a Subchannel will reset the "disabled" flag.
    assertThat(healthImpls[0].calls).hasSize(0);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    assertThat(healthImpls[0].calls).hasSize(1);
    serverCall0 = healthImpls[0].calls.poll();
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));

    // Health check now works as normal
    serverCall0.responseObserver.onNext(makeResponse(ServingStatus.SERVICE_UNKNOWN));
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        unavailableStateWithMsg("Health-check service responded SERVICE_UNKNOWN for 'BarService'"));

    verifyNoMoreInteractions(origLb, mockStateListeners[0], mockStateListeners[1]);
    verifyZeroInteractions(backoffPolicyProvider);
  }

  @Test
  public void backoffRetriesWhenServerErroneouslyClosesRpcBeforeAnyResponse() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);

    verify(origLb).handleResolvedAddresses(result);
    verifyNoMoreInteractions(origLb);

    FakeSubchannel subchannel = unwrap(createSubchannel(0, Attributes.EMPTY));
    assertThat(subchannel).isSameInstanceAs(subchannels[0]);
    SubchannelStateListener mockListener = mockStateListeners[0];
    InOrder inOrder = inOrder(mockListener, backoffPolicyProvider, backoffPolicy1, backoffPolicy2);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    HealthImpl healthImpl = healthImpls[0];
    assertThat(healthImpl.calls).hasSize(1);
    assertThat(clock.getPendingTasks()).isEmpty();

    subchannel.logs.clear();
    // Server closes the health checking RPC without any response
    healthImpl.calls.poll().responseObserver.onCompleted();

    // which results in TRANSIENT_FAILURE
    inOrder.verify(mockListener).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with " + Status.OK + " for 'TeeService'"));
    assertThat(subchannel.logs).containsExactly(
        "INFO: TRANSIENT_FAILURE: health-check stream closed with " + Status.OK,
        "DEBUG: Will retry health-check after 11 ns").inOrder();

    // Retry with backoff is scheduled
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(clock.getPendingTasks()).hasSize(1);

    verifyRetryAfterNanos(inOrder, mockListener, healthImpl, 11);
    assertThat(clock.getPendingTasks()).isEmpty();

    subchannel.logs.clear();
    // Server closes the health checking RPC without any response
    healthImpl.calls.poll().responseObserver.onError(Status.CANCELLED.asException());

    // which also results in TRANSIENT_FAILURE, with a different description
    inOrder.verify(mockListener).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with "
            + Status.CANCELLED + " for 'TeeService'"));
    assertThat(subchannel.logs).containsExactly(
        "INFO: TRANSIENT_FAILURE: health-check stream closed with " + Status.CANCELLED,
        "DEBUG: Will retry health-check after 21 ns").inOrder();

    // Retry with backoff
    inOrder.verify(backoffPolicy1).nextBackoffNanos();

    verifyRetryAfterNanos(inOrder, mockListener, healthImpl, 21);

    // Server responds this time
    healthImpl.calls.poll().responseObserver.onNext(makeResponse(ServingStatus.SERVING));

    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));

    verifyNoMoreInteractions(origLb, mockListener, backoffPolicyProvider, backoffPolicy1);
  }

  @Test
  public void serverRespondResetsBackoff() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);

    verify(origLb).handleResolvedAddresses(result);
    verifyNoMoreInteractions(origLb);

    SubchannelStateListener mockStateListener = mockStateListeners[0];
    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder =
        inOrder(mockStateListener, backoffPolicyProvider, backoffPolicy1, backoffPolicy2);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockStateListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    HealthImpl healthImpl = healthImpls[0];
    assertThat(healthImpl.calls).hasSize(1);
    assertThat(clock.getPendingTasks()).isEmpty();

    // Server closes the health checking RPC without any response
    healthImpl.calls.poll().responseObserver.onError(Status.CANCELLED.asException());

    // which results in TRANSIENT_FAILURE
    inOrder.verify(mockStateListener).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with "
            + Status.CANCELLED + " for 'TeeService'"));

    // Retry with backoff is scheduled
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(clock.getPendingTasks()).hasSize(1);

    verifyRetryAfterNanos(inOrder, mockStateListener, healthImpl, 11);
    assertThat(clock.getPendingTasks()).isEmpty();

    // Server responds
    healthImpl.calls.peek().responseObserver.onNext(makeResponse(ServingStatus.SERVING));
    inOrder.verify(mockStateListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));

    verifyNoMoreInteractions(mockStateListener);

    // then closes the stream
    healthImpl.calls.poll().responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(mockStateListener).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with "
            + Status.UNAVAILABLE + " for 'TeeService'"));

    // Because server has responded, the first retry is not subject to backoff.
    // But the backoff policy has been reset.  A new backoff policy will be used for
    // the next backed-off retry.
    inOrder.verify(mockStateListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    assertThat(healthImpl.calls).hasSize(1);
    assertThat(clock.getPendingTasks()).isEmpty();
    inOrder.verifyNoMoreInteractions();

    // then closes the stream for this retry
    healthImpl.calls.poll().responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(mockStateListener).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with "
            + Status.UNAVAILABLE + " for 'TeeService'"));

    // New backoff policy is used
    inOrder.verify(backoffPolicyProvider).get();
    // Retry with a new backoff policy
    inOrder.verify(backoffPolicy2).nextBackoffNanos();

    verifyRetryAfterNanos(inOrder, mockStateListener, healthImpl, 12);
  }

  private void verifyRetryAfterNanos(
      InOrder inOrder, SubchannelStateListener listener, HealthImpl impl,
      long nanos) {
    assertThat(impl.calls).isEmpty();
    clock.forwardNanos(nanos - 1);
    assertThat(impl.calls).isEmpty();
    inOrder.verifyNoMoreInteractions();
    verifyNoMoreInteractions(listener);
    clock.forwardNanos(1);
    inOrder.verify(listener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    assertThat(impl.calls).hasSize(1);
  }

  @Test
  public void serviceConfigHasNoHealthCheckingInitiallyButDoesLater() {
    // No service config, thus no health check.
    ResolvedAddresses result1 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(Attributes.EMPTY)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result1);

    verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb);

    // First, create Subchannels 0
    createSubchannel(0, Attributes.EMPTY);

    // No health check activity.  Underlying Subchannel states are directly propagated
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    assertThat(healthImpls[0].calls).isEmpty();
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));

    verifyNoMoreInteractions(mockStateListeners[0]);

    // Service config enables health check
    Attributes resolutionAttrs = attrsWithHealthCheckService("FooService");
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);
    verify(origLb).handleResolvedAddresses(result2);

    // Health check started on existing Subchannel
    assertThat(healthImpls[0].calls).hasSize(1);

    // State stays in READY, instead of switching to CONNECTING.
    verifyNoMoreInteractions(mockStateListeners[0]);

    // Start Subchannel 1, which will have health check
    createSubchannel(1, Attributes.EMPTY);
    assertThat(healthImpls[1].calls).isEmpty();
    deliverSubchannelState(1, ConnectivityStateInfo.forNonError(READY));
    assertThat(healthImpls[1].calls).hasSize(1);
  }

  @Test
  public void serviceConfigDisablesHealthCheckWhenRpcActive() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result1 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result1);

    verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb);

    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder = inOrder(origLb, mockStateListeners[0]);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    inOrder.verifyNoMoreInteractions();
    HealthImpl healthImpl = healthImpls[0];
    assertThat(healthImpl.calls).hasSize(1);
    ServerSideCall serverCall = healthImpl.calls.poll();
    assertThat(serverCall.cancelled).isFalse();

    // NameResolver gives an update without service config, thus health check will be disabled
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(Attributes.EMPTY)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);

    // Health check RPC cancelled.
    assertThat(serverCall.cancelled).isTrue();
    // Subchannel uses original state
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));

    inOrder.verify(origLb).handleResolvedAddresses(result2);

    verifyNoMoreInteractions(origLb, mockStateListeners[0]);
    assertThat(healthImpl.calls).isEmpty();
  }

  @Test
  public void serviceConfigDisablesHealthCheckWhenRetryPending() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);

    verify(origLb).handleResolvedAddresses(result);
    verifyNoMoreInteractions(origLb);

    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder = inOrder(origLb, mockStateListeners[0]);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    inOrder.verifyNoMoreInteractions();
    HealthImpl healthImpl = healthImpls[0];
    assertThat(healthImpl.calls).hasSize(1);

    // Server closes the stream without responding.  Client in retry backoff
    assertThat(clock.getPendingTasks()).isEmpty();
    healthImpl.calls.poll().responseObserver.onCompleted();
    assertThat(clock.getPendingTasks()).hasSize(1);
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with " + Status.OK + " for 'TeeService'"));

    // NameResolver gives an update without service config, thus health check will be disabled
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(Attributes.EMPTY)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);

    // Retry timer is cancelled
    assertThat(clock.getPendingTasks()).isEmpty();

    // No retry was attempted
    assertThat(healthImpl.calls).isEmpty();

    // Subchannel uses original state
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));

    inOrder.verify(origLb).handleResolvedAddresses(result2);

    verifyNoMoreInteractions(origLb, mockStateListeners[0]);
  }

  @Test
  public void serviceConfigDisablesHealthCheckWhenRpcInactive() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result1 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result1);

    verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb);

    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder = inOrder(origLb, mockStateListeners[0]);

    // Underlying subchannel is not READY initially
    ConnectivityStateInfo underlyingErrorState =
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("connection refused"));
    deliverSubchannelState(0, underlyingErrorState);
    inOrder.verify(mockStateListeners[0]).onSubchannelState(same(underlyingErrorState));
    inOrder.verifyNoMoreInteractions();

    // NameResolver gives an update without service config, thus health check will be disabled
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(Attributes.EMPTY)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);

    inOrder.verify(origLb).handleResolvedAddresses(result2);

    // Underlying subchannel is now ready
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));

    // Since health check is disabled, READY state is propagated directly.
    inOrder.verify(mockStateListeners[0]).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));

    // and there is no health check activity.
    assertThat(healthImpls[0].calls).isEmpty();

    verifyNoMoreInteractions(origLb, mockStateListeners[0]);
  }

  @Test
  public void serviceConfigChangesServiceNameWhenRpcActive() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result1 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result1);

    verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb);

    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    SubchannelStateListener mockListener = mockStateListeners[0];
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder = inOrder(origLb, mockListener);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));

    HealthImpl healthImpl = healthImpls[0];
    assertThat(healthImpl.calls).hasSize(1);
    ServerSideCall serverCall = healthImpl.calls.poll();
    assertThat(serverCall.cancelled).isFalse();
    assertThat(serverCall.request).isEqualTo(makeRequest("TeeService"));

    // Health check responded
    serverCall.responseObserver.onNext(makeResponse(ServingStatus.SERVING));
    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(READY)));

    // Service config returns with the same health check name.
    hcLbEventDelivery.handleResolvedAddresses(result1);
    // It's delivered to origLb, but nothing else happens
    inOrder.verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb, mockListener);

    // Service config returns a different health check name.
    resolutionAttrs = attrsWithHealthCheckService("FooService");
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);
    inOrder.verify(origLb).handleResolvedAddresses(result2);

    // Current health check RPC cancelled.
    assertThat(serverCall.cancelled).isTrue();

    // A second RPC is started immediately
    assertThat(healthImpl.calls).hasSize(1);
    serverCall = healthImpl.calls.poll();
    // with the new service name
    assertThat(serverCall.request).isEqualTo(makeRequest("FooService"));

    // State stays in READY, instead of switching to CONNECTING.
    verifyNoMoreInteractions(origLb, mockListener);
  }

  @Test
  public void serviceConfigChangesServiceNameWhenRetryPending() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result1 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result1);

    verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb);

    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    SubchannelStateListener mockListener = mockStateListeners[0];
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder = inOrder(origLb, mockListener);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));

    HealthImpl healthImpl = healthImpls[0];
    assertThat(healthImpl.calls).hasSize(1);
    ServerSideCall serverCall = healthImpl.calls.poll();
    assertThat(serverCall.cancelled).isFalse();
    assertThat(serverCall.request).isEqualTo(makeRequest("TeeService"));

    // Health check stream closed without responding.  Client in retry backoff.
    assertThat(clock.getPendingTasks()).isEmpty();
    serverCall.responseObserver.onCompleted();
    assertThat(clock.getPendingTasks()).hasSize(1);
    assertThat(healthImpl.calls).isEmpty();
    inOrder.verify(mockListener).onSubchannelState(
        unavailableStateWithMsg(
            "Health-check stream unexpectedly closed with " + Status.OK + " for 'TeeService'"));

    // Service config returns with the same health check name.

    hcLbEventDelivery.handleResolvedAddresses(result1);
    // It's delivered to origLb, but nothing else happens
    inOrder.verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb, mockListener);
    assertThat(clock.getPendingTasks()).hasSize(1);
    assertThat(healthImpl.calls).isEmpty();

    // Service config returns a different health check name.
    resolutionAttrs = attrsWithHealthCheckService("FooService");
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);

    // Concluded CONNECTING state
    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));

    inOrder.verify(origLb).handleResolvedAddresses(result2);

    // Current retry timer cancelled
    assertThat(clock.getPendingTasks()).isEmpty();

    // A second RPC is started immediately
    assertThat(healthImpl.calls).hasSize(1);
    serverCall = healthImpl.calls.poll();
    // with the new service name
    assertThat(serverCall.request).isEqualTo(makeRequest("FooService"));

    verifyNoMoreInteractions(origLb, mockListener);
  }

  @Test
  public void serviceConfigChangesServiceNameWhenRpcInactive() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result1 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result1);

    verify(origLb).handleResolvedAddresses(result1);
    verifyNoMoreInteractions(origLb);

    Subchannel subchannel = createSubchannel(0, Attributes.EMPTY);
    SubchannelStateListener mockListener = mockStateListeners[0];
    assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[0]);
    InOrder inOrder = inOrder(origLb, mockListener);
    HealthImpl healthImpl = healthImpls[0];

    // Underlying subchannel is not READY initially
    ConnectivityStateInfo underlyingErrorState =
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("connection refused"));
    deliverSubchannelState(0, underlyingErrorState);
    inOrder.verify(mockListener).onSubchannelState(same(underlyingErrorState));
    inOrder.verifyNoMoreInteractions();

    // Service config returns with the same health check name.
    hcLbEventDelivery.handleResolvedAddresses(result1);
    // It's delivered to origLb, but nothing else happens
    inOrder.verify(origLb).handleResolvedAddresses(result1);
    assertThat(healthImpl.calls).isEmpty();
    verifyNoMoreInteractions(origLb);

    // Service config returns a different health check name.
    resolutionAttrs = attrsWithHealthCheckService("FooService");
    ResolvedAddresses result2 = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result2);

    inOrder.verify(origLb).handleResolvedAddresses(result2);

    // Underlying subchannel is now ready
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));

    // Concluded CONNECTING state
    inOrder.verify(mockListener).onSubchannelState(
        eq(ConnectivityStateInfo.forNonError(CONNECTING)));

    // Health check RPC is started
    assertThat(healthImpl.calls).hasSize(1);
    // with the new service name
    assertThat(healthImpl.calls.poll().request).isEqualTo(makeRequest("FooService"));

    verifyNoMoreInteractions(origLb, mockListener);
  }

  @Test
  public void getHealthCheckedServiceName_nullServiceConfig() {
    assertThat(ServiceConfigUtil.getHealthCheckedServiceName(null)).isNull();
  }

  @Test
  public void getHealthCheckedServiceName_noHealthCheckConfig() {
    assertThat(ServiceConfigUtil.getHealthCheckedServiceName(new HashMap<String, Void>())).isNull();
  }

  @Test
  public void getHealthCheckedServiceName_healthCheckConfigMissingServiceName() {
    HashMap<String, Object> serviceConfig = new HashMap<>();
    HashMap<String, Object> hcConfig = new HashMap<>();
    serviceConfig.put("healthCheckConfig", hcConfig);
    assertThat(ServiceConfigUtil.getHealthCheckedServiceName(serviceConfig)).isNull();
  }

  @Test
  public void getHealthCheckedServiceName_healthCheckConfigHasServiceName() {
    HashMap<String, Object> serviceConfig = new HashMap<>();
    HashMap<String, Object> hcConfig = new HashMap<>();
    hcConfig.put("serviceName", "FooService");
    serviceConfig.put("healthCheckConfig", hcConfig);
    assertThat(ServiceConfigUtil.getHealthCheckedServiceName(serviceConfig))
        .isEqualTo("FooService");
  }

  @Test
  public void balancerShutdown() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("TeeService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);

    verify(origLb).handleResolvedAddresses(result);
    verifyNoMoreInteractions(origLb);
    ServerSideCall[] serverCalls = new ServerSideCall[NUM_SUBCHANNELS];

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      Subchannel subchannel = createSubchannel(i, Attributes.EMPTY);
      SubchannelStateListener mockListener = mockStateListeners[i];
      assertThat(unwrap(subchannel)).isSameInstanceAs(subchannels[i]);

      // Trigger the health check
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(READY));

      HealthImpl healthImpl = healthImpls[i];
      assertThat(healthImpl.calls).hasSize(1);
      serverCalls[i] = healthImpl.calls.poll();
      assertThat(serverCalls[i].cancelled).isFalse();

      verify(mockListener).onSubchannelState(
          eq(ConnectivityStateInfo.forNonError(CONNECTING)));
    }

    // Shut down the balancer
    hcLbEventDelivery.shutdown();
    verify(origLb).shutdown();

    // Health check stream should be cancelled
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      assertThat(serverCalls[i].cancelled).isTrue();
      // LoadBalancer API requires no more callbacks on LoadBalancer after shutdown() is called.
      verifyNoMoreInteractions(origLb, mockStateListeners[i]);
      // No more health check call is made or scheduled
      assertThat(healthImpls[i].calls).isEmpty();
    }

    assertThat(clock.getPendingTasks()).isEmpty();
  }

  @Test
  public void util_newHealthCheckingLoadBalancer() {
    Factory hcFactory =
        new Factory() {
          @Override
          public LoadBalancer newLoadBalancer(Helper helper) {
            return HealthCheckingLoadBalancerUtil.newHealthCheckingLoadBalancer(
                origLbFactory, helper);
          }
        };

    // hcLb and wrappedHelper are already set in setUp().  For this special test case, we
    // clear wrappedHelper so that we can create hcLb again with the util.
    wrappedHelper = null;
    hcLb = hcFactory.newLoadBalancer(origHelper);

    // Verify that HC works
    Attributes resolutionAttrs = attrsWithHealthCheckService("BarService");
    ResolvedAddresses result = ResolvedAddresses.newBuilder()
        .setAddresses(resolvedAddressList)
        .setAttributes(resolutionAttrs)
        .build();
    hcLbEventDelivery.handleResolvedAddresses(result);
    verify(origLb).handleResolvedAddresses(result);
    createSubchannel(0, Attributes.EMPTY);
    assertThat(healthImpls[0].calls).isEmpty();
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    assertThat(healthImpls[0].calls).hasSize(1);
  }

  private Attributes attrsWithHealthCheckService(@Nullable String serviceName) {
    HashMap<String, Object> serviceConfig = new HashMap<>();
    HashMap<String, Object> hcConfig = new HashMap<>();
    hcConfig.put("serviceName", serviceName);
    serviceConfig.put("healthCheckConfig", hcConfig);
    return Attributes.newBuilder()
        .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig).build();
  }

  private HealthCheckRequest makeRequest(String service) {
    return HealthCheckRequest.newBuilder().setService(service).build();
  }

  private HealthCheckResponse makeResponse(ServingStatus status) {
    return HealthCheckResponse.newBuilder().setStatus(status).build();
  }

  private ConnectivityStateInfo unavailableStateWithMsg(final String expectedMsg) {
    return MockitoHamcrest.argThat(
        new org.hamcrest.BaseMatcher<ConnectivityStateInfo>() {
          @Override
          public boolean matches(Object item) {
            if (!(item instanceof ConnectivityStateInfo)) {
              return false;
            }
            ConnectivityStateInfo info = (ConnectivityStateInfo) item;
            if (!info.getState().equals(TRANSIENT_FAILURE)) {
              return false;
            }
            Status error = info.getStatus();
            if (!error.getCode().equals(Code.UNAVAILABLE)) {
              return false;
            }
            if (!error.getDescription().equals(expectedMsg)) {
              return false;
            }
            return true;
          }

          @Override
          public void describeTo(org.hamcrest.Description desc) {
            desc.appendText("Matches unavailable state with msg='" + expectedMsg + "'");
          }
        });
  }

  private static class HealthImpl extends HealthGrpc.HealthImplBase {
    boolean checkCalled;
    final Queue<ServerSideCall> calls = new ArrayDeque<>();

    @Override
    public void check(HealthCheckRequest request,
        StreamObserver<HealthCheckResponse> responseObserver) {
      responseObserver.onError(new UnsupportedOperationException("Should never be called"));
      checkCalled = true;
    }

    @Override
    public void watch(HealthCheckRequest request,
        StreamObserver<HealthCheckResponse> responseObserver) {
      final ServerSideCall call = new ServerSideCall(request, responseObserver);
      Context.current().addListener(
          new CancellationListener() {
            @Override
            public void cancelled(Context ctx) {
              call.cancelled = true;
            }
          }, MoreExecutors.directExecutor());
      calls.add(call);
    }
  }

  private static class ServerSideCall {
    final HealthCheckRequest request;
    final StreamObserver<HealthCheckResponse> responseObserver;
    boolean cancelled;

    ServerSideCall(
        HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
      this.request = request;
      this.responseObserver = responseObserver;
    }
  }

  private class FakeSubchannel extends Subchannel {
    final List<EquivalentAddressGroup> eagList;
    final Attributes attrs;
    final Channel channel;
    final ArrayList<String> logs = new ArrayList<>();
    final int index;
    SubchannelStateListener listener;
    boolean isShutdown;
    private final ChannelLogger logger = new ChannelLogger() {
        @Override
        public void log(ChannelLogLevel level, String msg) {
          logs.add(level + ": " + msg);
        }

        @Override
        public void log(ChannelLogLevel level, String template, Object... args) {
          log(level, MessageFormat.format(template, args));
        }
      };

    FakeSubchannel(int index, CreateSubchannelArgs args, Channel channel) {
      this.index = index;
      this.eagList = args.getAddresses();
      this.attrs = args.getAttributes();
      this.channel = checkNotNull(channel);
    }

    @Override
    public void start(SubchannelStateListener listener) {
      checkState(this.listener == null);
      this.listener = listener;
    }

    @Override
    public void shutdown() {
      isShutdown = true;
      deliverSubchannelState(index, ConnectivityStateInfo.forNonError(SHUTDOWN));
    }

    @Override
    public void requestConnection() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eagList;
    }

    @Override
    public Attributes getAttributes() {
      return attrs;
    }

    @Override
    public Channel asChannel() {
      return channel;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return logger;
    }
  }

  private class FakeHelper extends Helper {
    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      int index = -1;
      for (int i = 0; i < NUM_SUBCHANNELS; i++) {
        if (eagLists[i].equals(args.getAddresses())) {
          index = i;
          break;
        }
      }
      checkState(index >= 0, "addrs " + args.getAddresses() + " not found");
      FakeSubchannel subchannel = new FakeSubchannel(index, args, channels[index]);
      checkState(subchannels[index] == null, "subchannels[" + index + "] already created");
      subchannels[index] = subchannel;
      return subchannel;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return clock.getScheduledExecutorService();
    }

    @Deprecated
    @Override
    public NameResolver.Factory getNameResolverFactory() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public String getAuthority() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new AssertionError("Should not be called");
    }
  }

  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  // In reality wrappedHelper.createSubchannel() is always called from syncContext.
  // Make sure it's the case in the test too.
  private Subchannel createSubchannel(final int index, final Attributes attrs) {
    final AtomicReference<Subchannel> returnedSubchannel = new AtomicReference<>();
    syncContext.execute(new Runnable() {
        @Override
        public void run() {
          Subchannel s = wrappedHelper.createSubchannel(CreateSubchannelArgs.newBuilder()
              .setAddresses(eagLists[index])
              .setAttributes(attrs)
              .build());
          s.start(mockStateListeners[index]);
          returnedSubchannel.set(s);
        }
      });
    return returnedSubchannel.get();
  }

  private void deliverSubchannelState(final int index, final ConnectivityStateInfo newState) {
    syncContext.execute(new Runnable() {
        @Override
        public void run() {
          subchannels[index].listener.onSubchannelState(newState);
        }
      });
  }

  private static FakeSubchannel unwrap(Subchannel s) {
    return (FakeSubchannel) ((SubchannelImpl) s).delegate();
  }
}
