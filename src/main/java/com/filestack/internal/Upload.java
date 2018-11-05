package com.filestack.internal;

import com.filestack.Config;
import com.filestack.FileLink;
import com.filestack.Progress;
import com.filestack.StorageOptions;
import com.filestack.internal.responses.StartResponse;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Holds upload state and request logic. */
public class Upload {
  static final int PROG_INTERVAL_SEC = 2;
  static final int DELAY_BASE = 2;
  static final int INTELLIGENT_PART_SIZE = 8 * 1024 * 1024;
  static final int REGULAR_PART_SIZE = 5 * 1024 * 1024;
  static final int CONCURRENCY = 4;

  private static final int INITIAL_CHUNK_SIZE = 1024 * 1024;
  private static final int MIN_CHUNK_SIZE = 32 * 1024;

  private final UploadService uploadService;
  // These should never change once set
  final Config clientConf;
  final int inputSize;
  final StorageOptions storageOptions;

  // Not bothering with getters / setters for these
  boolean intel;
  int partSize;
  Map<String, RequestBody> baseParams;
  MediaType mediaType;
  String[] etags;

  // Access to these is controlled and synchronized
  private final InputStream input;
  private int chunkSize;
  private int partIndex;

  /** Constructs new instance. */
  public Upload(Config clientConf, UploadService uploadService, InputStream input, int inputSize, boolean intel,
                StorageOptions storeOpts) {
    this.clientConf = clientConf;
    this.uploadService = uploadService;
    this.input = input;
    this.inputSize = inputSize;
    this.intel = intel;
    this.partIndex = 1;
    this.chunkSize = INITIAL_CHUNK_SIZE;
    this.storageOptions = storeOpts;

    // Setup base parameters that get used repeatedly for backend requests
    baseParams = new HashMap<>();

    safePut(baseParams,"filename", storeOpts.getFilename());
    safePut(baseParams,"mimetype", storeOpts.getMimeType());
    safePut(baseParams,"store_location", storeOpts.getLocation());
    safePut(baseParams,"store_region", storeOpts.getRegion());
    safePut(baseParams,"store_container", storeOpts.getContainer());
    safePut(baseParams,"store_path", storeOpts.getPath());
    safePut(baseParams,"store_access", storeOpts.getAccess());
    
    baseParams.put("apikey", Util.createStringPart(clientConf.getApiKey()));
    baseParams.put("size", Util.createStringPart(Integer.toString(inputSize)));

    // Key name is a misnomer, all uploads are multipart, this is for "intelligent" uploads
    // If the account doesn't support it, we'll fall back to regular multipart after start request
    // This param should not be added by default and the  later removed to match the "intel" param
    // It should only be set if the "intel" param was set to true
    // If it's set by default, the account supports it, and it's removed *after* the call to start,
    // uploads <= a the regular part size will fail
    if (intel) {
      baseParams.put("multipart", Util.createStringPart("true"));
    }

    if (clientConf.hasSecurity()) {
      baseParams.put("policy", Util.createStringPart(clientConf.getPolicy()));
      baseParams.put("signature", Util.createStringPart(clientConf.getSignature()));
    }

    // This needs to be called after "getAsPartMap"
    this.mediaType = MediaType.parse(storeOpts.getMimeType());
  }

  private void safePut(Map<String, RequestBody> map, String key, String value) {
    if (value != null) {
      map.put(key, Util.createStringPart(value));
    }
  }

  /**
   * Read from the input stream into a simple container object. Synchronized to support concurrent
   * worker threads. The part object should be created once and reused to keep mem usage and garbage
   * collection down.
   */
  synchronized int readInput(PartContainer container) throws IOException {
    container.num = partIndex;
    container.size = input.read(container.data);
    container.sent = 0;
    partIndex++;
    return container.size;
  }

  // TODO Synchronizing access to chunk size like this may incur too much of a performance penalty

  /**
   * Upload calls in different workers/threads may fail and reduce the intelligent chunk size. We
   * want to synchronize reading this variable so the value is always accurate across threads.
   */
  synchronized int getChunkSize() {
    return chunkSize;
  }

  /**
   * Upload calls in different workers/threads may fail and reduce the intelligent chunk size. We
   * want to synchronize updating this variable so the value is always accurate across threads.
   *
   * @throws IOException when too many requests have failed and the chunk size can't be reduced
   */
  synchronized void reduceChunkSize() throws IOException {
    chunkSize /= 2;
    if (chunkSize < MIN_CHUNK_SIZE) {
      throw new IOException();
    }
  }

  /**
   * Start this upload asynchronously. Returns progress updates.
   *
   * @return {@link Flowable} that emits {@link Progress} events
   */
  public Flowable<Progress<FileLink>> run() {
    Flowable<StartResponse> startFlow = Flowable
        .fromCallable(new UploadStartFunc(uploadService, this))
        .subscribeOn(Schedulers.io());

    Flowable<Prog> transferFlow = Flowable.empty();
    for (int i = 0; i < CONCURRENCY; i++) {
      UploadTransferFunc func = new UploadTransferFunc(uploadService, this);
      Flowable<Prog> temp = Flowable
          .create(func, BackpressureStrategy.BUFFER)
          .subscribeOn(Schedulers.io());
      transferFlow = transferFlow.mergeWith(temp);
    }

    final Flowable<Prog> finalTransferFlow = transferFlow;
    return startFlow
        .flatMap(new Function<StartResponse, Publisher<Prog>>() {
          @Override
          public Publisher<Prog> apply(StartResponse startResponse) {
            return finalTransferFlow;
          }
        })
        .concatWith(transferFlow)
        .concatWith(Flowable.fromCallable(new UploadCompleteFunc(uploadService, this)))
        .flatMap(new ProgMapFunc(this));
  }
}
