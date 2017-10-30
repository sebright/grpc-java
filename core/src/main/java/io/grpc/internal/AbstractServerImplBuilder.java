/*
 * Copyright 2014, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Internal;
import io.grpc.InternalNotifyOnServerBuild;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.trace.Tracing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * The base class for server builders.
 *
 * @param <T> The concrete type for this builder.
 */
public abstract class AbstractServerImplBuilder<T extends AbstractServerImplBuilder<T>>
        extends ServerBuilder<T> {

  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  private static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  private static final HandlerRegistry DEFAULT_FALLBACK_REGISTRY = new HandlerRegistry() {
      @Override
      public List<ServerServiceDefinition> getServices() {
        return Collections.emptyList();
      }

      @Override
      public ServerMethodDefinition<?, ?> lookupMethod(String methodName,
          @Nullable String authority) {
        return null;
      }
    };
  private static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();
  private static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
      CompressorRegistry.getDefaultInstance();

  final InternalHandlerRegistry.Builder registryBuilder =
      new InternalHandlerRegistry.Builder();

  final List<ServerTransportFilter> transportFilters =
      new ArrayList<ServerTransportFilter>();

  final List<ServerInterceptor> interceptors = new ArrayList<ServerInterceptor>();

  private final List<InternalNotifyOnServerBuild> notifyOnBuildList =
      new ArrayList<InternalNotifyOnServerBuild>();

  private final List<ServerStreamTracer.Factory> streamTracerFactories =
      new ArrayList<ServerStreamTracer.Factory>();

  HandlerRegistry fallbackRegistry = DEFAULT_FALLBACK_REGISTRY;

  ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;

  DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;

  CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;

  @Nullable
  private Tagger tagger;

  @Nullable
  private TagContextBinarySerializer tagCtxSerializer;

  @Nullable
  private StatsRecorder statsRecorder;

  private boolean statsEnabled = true;
  private boolean recordStats = true;
  private boolean tracingEnabled = true;

  @Override
  public final T directExecutor() {
    return executor(MoreExecutors.directExecutor());
  }

  @Override
  public final T executor(@Nullable Executor executor) {
    if (executor != null) {
      this.executorPool = new FixedObjectPool<Executor>(executor);
    } else {
      this.executorPool = DEFAULT_EXECUTOR_POOL;
    }
    return thisT();
  }

  @Override
  public final T addService(ServerServiceDefinition service) {
    registryBuilder.addService(service);
    return thisT();
  }

  @Override
  public final T addService(BindableService bindableService) {
    if (bindableService instanceof InternalNotifyOnServerBuild) {
      notifyOnBuildList.add((InternalNotifyOnServerBuild) bindableService);
    }
    return addService(bindableService.bindService());
  }

  @Override
  public final T addTransportFilter(ServerTransportFilter filter) {
    transportFilters.add(checkNotNull(filter, "filter"));
    return thisT();
  }

  @Override
  public final T intercept(ServerInterceptor interceptor) {
    interceptors.add(interceptor);
    return thisT();
  }

  @Override
  public final T addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    streamTracerFactories.add(checkNotNull(factory, "factory"));
    return thisT();
  }

  @Override
  public final T fallbackHandlerRegistry(HandlerRegistry registry) {
    if (registry != null) {
      this.fallbackRegistry = registry;
    } else {
      this.fallbackRegistry = DEFAULT_FALLBACK_REGISTRY;
    }
    return thisT();
  }

  @Override
  public final T decompressorRegistry(DecompressorRegistry registry) {
    if (registry != null) {
      decompressorRegistry = registry;
    } else {
      decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;
    }
    return thisT();
  }

  @Override
  public final T compressorRegistry(CompressorRegistry registry) {
    if (registry != null) {
      compressorRegistry = registry;
    } else {
      compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;
    }
    return thisT();
  }

  /**
   * Override the default stats implementation.
   */
  @VisibleForTesting
  protected T statsImplementation(
      final Tagger tagger,
      TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder) {
    this.tagger = tagger;
    this.tagCtxSerializer = tagCtxSerializer;
    this.statsRecorder = statsRecorder;
    return thisT();
  }

  /**
   * Disable or enable stats features.  Enabled by default.
   */
  protected void setStatsEnabled(boolean value) {
    statsEnabled = value;
  }

  /**
   * Disable or enable stats recording.  Effective only if {@link #setStatsEnabled} is set to true.
   * Enabled by default.
   */
  protected void setRecordStats(boolean value) {
    recordStats = value;
  }

  /**
   * Disable or enable tracing features.  Enabled by default.
   */
  protected void setTracingEnabled(boolean value) {
    tracingEnabled = value;
  }

  @Override
  public Server build() {
    ServerImpl server = new ServerImpl(
        this,
        buildTransportServer(Collections.unmodifiableList(getTracerFactories())),
        Context.ROOT);
    for (InternalNotifyOnServerBuild notifyTarget : notifyOnBuildList) {
      notifyTarget.notifyOnBuild(server);
    }
    return server;
  }

  @VisibleForTesting
  final List<ServerStreamTracer.Factory> getTracerFactories() {
    ArrayList<ServerStreamTracer.Factory> tracerFactories =
        new ArrayList<ServerStreamTracer.Factory>();
    if (statsEnabled) {
      Tagger tagger = this.tagger != null ? this.tagger : Tags.getTagger();
      TagContextBinarySerializer tagCtxSerializer =
          this.tagCtxSerializer != null
              ? this.tagCtxSerializer
              : Tags.getTagPropagationComponent().getBinarySerializer();
      StatsRecorder statsRecorder =
          this.statsRecorder != null ? this.statsRecorder : Stats.getStatsRecorder();
      // How do we check whether stats is enabled? Uncommenting this line causes test failures.
      // //    if (Stats.getState() == StatsCollectionState.ENABLED) {
      CensusStatsModule censusStats =
          new CensusStatsModule(
              tagger,
              tagCtxSerializer,
              statsRecorder,
              GrpcUtil.STOPWATCH_SUPPLIER,
              true,
              recordStats);
      tracerFactories.add(censusStats.getServerTracerFactory());
      }
    //      }
    if (tracingEnabled) {
      CensusTracingModule censusTracing =
          new CensusTracingModule(Tracing.getTracer(),
              Tracing.getPropagationComponent().getBinaryFormat());
      tracerFactories.add(censusTracing.getServerTracerFactory());
    }
    tracerFactories.addAll(streamTracerFactories);
    return tracerFactories;
  }

  /**
   * Children of AbstractServerBuilder should override this method to provide transport specific
   * information for the server.  This method is mean for Transport implementors and should not be
   * used by normal users.
   *
   * @param streamTracerFactories an immutable list of stream tracer factories
   */
  @Internal
  protected abstract io.grpc.internal.InternalServer buildTransportServer(
      List<ServerStreamTracer.Factory> streamTracerFactories);

  private T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
