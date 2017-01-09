package org.frontcache.hystrix.stream;

import org.frontcache.FrontCacheEngine;
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCollapserMetrics;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.metric.consumer.HystrixDashboardStream;
import com.netflix.hystrix.serial.SerialHystrixDashboardData;

import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 */
public abstract class FrontcacheHystrixSampleSseServlet extends HttpServlet {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 7939894788065973626L;

	protected final Observable<HystrixDashboardStream.DashboardData> originStream;

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheHystrixSampleSseServlet.class);

	// wake up occasionally and check that poller is still alive. this value
	// controls how often
	protected static final int DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS = 500;

	private final int pausePollerThreadDelayInMs;

	/*
	 * Set to true upon shutdown, so it's OK to be shared among all
	 * SampleSseServlets
	 */
	private static volatile boolean isDestroyed = false;

	protected FrontcacheHystrixSampleSseServlet(Observable<HystrixDashboardStream.DashboardData> sampleStream,
			int pausePollerThreadDelayInMs) {
		this.originStream =  sampleStream;
		this.pausePollerThreadDelayInMs = pausePollerThreadDelayInMs;
	}

	public static List<String> toMultipleJsonStrings(HystrixDashboardStream.DashboardData dashboardData, String domain) {
		List<String> jsonStrings = new ArrayList<String>();

		for (HystrixCommandMetrics commandMetrics : dashboardData.getCommandMetrics()) {
			if (isOwnerGroup(commandMetrics, domain)) {
				jsonStrings.add(SerialHystrixDashboardData.toJsonString(commandMetrics));
			}
		}

		for (HystrixThreadPoolMetrics threadPoolMetrics : dashboardData.getThreadPoolMetrics()) {
			if (isOwnerGroup(threadPoolMetrics, domain)) {
				jsonStrings.add(SerialHystrixDashboardData.toJsonString(threadPoolMetrics));
			}
		}

		for (HystrixCollapserMetrics collapserMetrics : dashboardData.getCollapserMetrics()) {
			if (isOwnerGroup(collapserMetrics, domain)) {
			   jsonStrings.add(SerialHystrixDashboardData.toJsonString(collapserMetrics));
			}
		}

		return jsonStrings;
	}

	private static boolean isOwnerGroup(HystrixThreadPoolMetrics commandMetrics, final String siteKey) {
		logger.info("commandMetrics.getThreadPoolKey().name() {}",  commandMetrics.getThreadPoolKey().name());
		if (commandMetrics.getThreadPoolKey().name().equals(siteKey)) {
			return true;
		} else {
			return false;
		}

	}

	private static boolean isOwnerGroup(HystrixCollapserMetrics collapserMetrics, final String siteKey) {
		logger.info("collapserMetrics.getCollapserKey().name() {}", collapserMetrics.getCollapserKey().name());

		if (collapserMetrics.getCollapserKey().name().equals(siteKey)) {
			return true;
		} else {
			return false;
		}

	}
	
	private static boolean isOwnerGroup(HystrixCommandMetrics commandMetrics, final String siteKey) {
		logger.info("commandMetrics.getCommandGroup().name() {}", commandMetrics.getCommandGroup().name());

		if (commandMetrics.getCommandGroup().name().equals(siteKey)) {
			return true;
		} else {
			return false;
		}

	}

	protected abstract int getMaxNumberConcurrentConnectionsAllowed();

	protected abstract int getNumberCurrentConnections();

	protected abstract int incrementAndGetCurrentConcurrentConnections();

	protected abstract void decrementCurrentConcurrentConnections();

	/**
	 * Handle incoming GETs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (isDestroyed) {
			response.sendError(503, "Service has been shut down.");
		} else {
			handleRequest(request, response);
		}
	}

	/**
	 * WebSphere won't shutdown a servlet until after a 60 second timeout if
	 * there is an instance of the servlet executing a request. Add this method
	 * to enable a hook to notify Hystrix to shutdown. You must invoke this
	 * method at shutdown, perhaps from some other servlet's destroy() method.
	 */
	public static void shutdown() {
		isDestroyed = true;
	}

	@Override
	public void init() throws ServletException {
		isDestroyed = false;
	}

	/**
	 * Handle servlet being undeployed by gracefully releasing connections so
	 * poller threads stop.
	 */
	@Override
	public void destroy() {
		/* set marker so the loops can break out */
		isDestroyed = true;
		super.destroy();
	}


	
	/**
	 * - maintain an open connection with the client - on initial connection
	 * send latest data of each requested event type - subsequently send all
	 * changes for each requested event type
	 *
	 * @param request
	 *            incoming HTTP Request
	 * @param response
	 *            outgoing HTTP Response (as a streaming response)
	 * @throws javax.servlet.ServletException
	 * @throws java.io.IOException
	 */
	private void handleRequest(HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {
		final AtomicBoolean moreDataWillBeSent = new AtomicBoolean(true);
		Subscription sampleSubscription = null;

		/* ensure we aren't allowing more connections than we want */
		int numberConnections = incrementAndGetCurrentConcurrentConnections();
		try {
			
			final String domain = FCUtils.getDomainFromSiteKeyHeader(request);
			if (domain == null){
				response.sendError(503, "Can't resolve domain from siteKey");
			}
			
			int maxNumberConnectionsAllowed = getMaxNumberConcurrentConnectionsAllowed();
			if (numberConnections > maxNumberConnectionsAllowed) {
				response.sendError(503, "MaxConcurrentConnections reached: " + maxNumberConnectionsAllowed);
			} else {
				/* initialize response */
				response.setHeader("Content-Type", "text/event-stream;charset=UTF-8");
				response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
				response.setHeader("Pragma", "no-cache");

				final PrintWriter writer = response.getWriter();

				// since the sample stream is based on Observable.interval,
				// events will get published on an RxComputation thread
				// since writing to the servlet response is blocking, use the Rx
				// IO thread for the write that occurs in the onNext
				
				originStream.concatMap(new Func1<HystrixDashboardStream.DashboardData, Observable<String>>() {
					@Override
					public Observable<String> call(HystrixDashboardStream.DashboardData dashboardData) {
						return Observable.from(toMultipleJsonStrings(dashboardData, domain));
					}
				}).observeOn(Schedulers.io()).subscribe(new Subscriber<String>() {
				
					@Override
					public void onCompleted() {
						logger.error("HystrixSampleSseServlet: ({}) received unexpected OnCompleted from sample stream",
								getClass().getSimpleName());
						moreDataWillBeSent.set(false);
					}

					@Override
					public void onError(Throwable e) {
						moreDataWillBeSent.set(false);
					}

					@Override
					public void onNext(String sampleDataAsString) {
						if (sampleDataAsString != null) {
							try {
								writer.print("data: " + sampleDataAsString + "\n\n");
								// explicitly check for client disconnect -
								// PrintWriter does not throw exceptions
								if (writer.checkError()) {
									throw new IOException("io error");
								}
								writer.flush();
							} catch (IOException ioe) {
								moreDataWillBeSent.set(false);
							}
						}
					}
				});

				while (moreDataWillBeSent.get() && !isDestroyed) {
					try {
						Thread.sleep(pausePollerThreadDelayInMs);
					} catch (InterruptedException e) {
						moreDataWillBeSent.set(false);
					}
				}
			}
		} finally {
			decrementCurrentConcurrentConnections();
			if (sampleSubscription != null && !sampleSubscription.isUnsubscribed()) {
				sampleSubscription.unsubscribe();
			}
		}
	}
}
