package edu.cmu.ml.proppr;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import edu.cmu.ml.proppr.examples.PosNegRWExample;
import edu.cmu.ml.proppr.graph.LearningGraphBuilder;
import edu.cmu.ml.proppr.learn.AdaGradSRW;
import edu.cmu.ml.proppr.learn.SRW;
import edu.cmu.ml.proppr.learn.tools.LossData;
import edu.cmu.ml.proppr.learn.tools.StoppingCriterion;
import edu.cmu.ml.proppr.util.SymbolTable;
import edu.cmu.ml.proppr.util.math.ParamVector;
import edu.cmu.ml.proppr.util.math.SimpleParamVector;
import edu.cmu.ml.proppr.util.multithreading.Multithreading;
import edu.cmu.ml.proppr.util.multithreading.NamedThreadFactory;

/**
 * Version of the Trainer class which uses Adaptive Sub Gradient method (AdaGrad) instead of 
 * Stochastic Gradient Descent 
 * 
 * @author rosecatherinek
 *
 */
public class AdaGradTrainer extends Trainer {

	protected AdaGradSRW agLearner;

	public AdaGradTrainer(SRW agLearner, int nthreads, int throttle) {
		super(agLearner, nthreads, throttle);
		this.agLearner = (AdaGradSRW) agLearner;
	}

	public AdaGradTrainer(SRW agSRW) {
		this(agSRW, 1, Multithreading.DEFAULT_THROTTLE);
	}


	public ParamVector train(SymbolTable<String> masterFeatures, Iterable<String> examples, LearningGraphBuilder builder, ParamVector initialParamVec, int numEpochs, boolean traceLosses) {
		ParamVector paramVec = this.agLearner.setupParams(initialParamVec);
		if (paramVec.size() == 0){
			for (String f : this.agLearner.untrainedFeatures()) paramVec.put(f, this.agLearner.getSquashingFunction().defaultValue());
		}

		//@rck AG
		//create a cuncurrent hash map to store the running total of the squares of the gradient
		SimpleParamVector<String> totSqGrad = new SimpleParamVector<String>(new ConcurrentHashMap<String,Double>(DEFAULT_CAPACITY,DEFAULT_LOAD,this.nthreads)); 

		if (masterFeatures.size()>0) LearningGraphBuilder.setFeatures(masterFeatures);
		NamedThreadFactory workingThreads = new NamedThreadFactory("work-");
		NamedThreadFactory cleaningThreads = new NamedThreadFactory("cleanup-");
		ThreadPoolExecutor workingPool;
		ExecutorService cleanPool; 
		TrainingStatistics total = new TrainingStatistics();
		StoppingCriterion stopper = new StoppingCriterion(numEpochs, this.stoppingPercent, this.stoppingEpoch);

		// repeat until ready to stop
		while (!stopper.satisified()) {
			// set up current epoch
			this.epoch++;
			this.agLearner.setEpoch(epoch);
			log.info("epoch "+epoch+" ...");

			// reset counters & file pointers
			this.agLearner.clearLoss();
			this.statistics = new TrainingStatistics();
			workingThreads.reset();
			cleaningThreads.reset();

			workingPool = new ThreadPoolExecutor(this.nthreads,Integer.MAX_VALUE,10,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(),workingThreads);
			cleanPool = Executors.newSingleThreadExecutor(cleaningThreads);

			// run examples
			int id=1;
			long start = System.currentTimeMillis();
			int countdown=-1; AdaGradTrainer notify = null;
			for (String s : examples) {
				if (log.isDebugEnabled()) log.debug("Queue size "+(workingPool.getTaskCount()-workingPool.getCompletedTaskCount()));
				statistics.updateReadingStatistics(System.currentTimeMillis()-start);
				if (countdown>0) {
					if (log.isDebugEnabled()) log.debug("Countdown "+countdown);
					countdown--;
				} else if (countdown == 0) {
					if (log.isDebugEnabled()) log.debug("Countdown "+countdown +"; throttling:");
					countdown--;
					notify = null;
					try {
						synchronized(this) {
							if (log.isDebugEnabled()) log.debug("Clearing training queue...");
							while(workingPool.getTaskCount()-workingPool.getCompletedTaskCount() > this.nthreads)
								this.wait();
							if (log.isDebugEnabled()) log.debug("Queue cleared.");
						}
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				} else if (workingPool.getTaskCount()-workingPool.getCompletedTaskCount() > 1.5*this.nthreads) {
					if (log.isDebugEnabled()) log.debug("Starting countdown");
					countdown=this.nthreads;
					notify = this;
				}
				Future<PosNegRWExample> parsed = workingPool.submit(new Parse(s, builder, id));
				Future<Integer> trained = workingPool.submit(new AdaGradTrain(parsed, paramVec, totSqGrad, agLearner, id, notify));
				cleanPool.submit(new TraceLosses(trained, id));
				id++;
				start = System.currentTimeMillis();
			}

			workingPool.shutdown();
			try {
				workingPool.awaitTermination(7, TimeUnit.DAYS);
				cleanPool.shutdown();
				cleanPool.awaitTermination(7, TimeUnit.DAYS);
			} catch (InterruptedException e) {
				log.error("Interrupted?",e);
			}

			// finish any trailing updates for this epoch
			this.agLearner.cleanupParams(paramVec,paramVec);

			// loss status and signalling the stopper
			if(traceLosses) {
				LossData lossThisEpoch = this.agLearner.cumulativeLoss();
				lossThisEpoch.convertCumulativesToAverage(statistics.numExamplesThisEpoch);
				printLossOutput(lossThisEpoch);
				if (epoch>1) {
					stopper.recordConsecutiveLosses(lossThisEpoch,lossLastEpoch);
				}
				lossLastEpoch = lossThisEpoch;
			}
			stopper.recordEpoch();
			statistics.checkStatistics();
			total.updateReadingStatistics(statistics.readTime);
			total.updateParsingStatistics(statistics.parseTime);
			total.updateTrainingStatistics(statistics.trainTime);
		}
		log.info("Reading  statistics: min "+total.minReadTime+" / max "+total.maxReadTime+" / total "+total.readTime);
		log.info("Parsing  statistics: min "+total.minParseTime+" / max "+total.maxParseTime+" / total "+total.parseTime);
		log.info("Training statistics: min "+total.minTrainTime+" / max "+total.maxTrainTime+" / total "+total.trainTime);
		log.info("Reading: "+total.readTime+" Parsing: "+total.parseTime+" Training: "+total.trainTime + " Num Epochs: " + this.epoch);
		return paramVec;
	}


	public ParamVector findGradient(Iterable<String> examples, LearningGraphBuilder builder, ParamVector paramVec, SimpleParamVector<String> totSqGrad) {
		log.info("Computing gradient on cooked examples...");
		ParamVector sumGradient = new SimpleParamVector<String>();
		if (paramVec==null) {
			paramVec = createParamVector();
			for (String f : this.agLearner.untrainedFeatures()) paramVec.put(f, 1.0); // FIXME: should this use the weighter default?
		}
		paramVec = this.agLearner.setupParams(paramVec);

		//		
		//		//WW: accumulate example-size normalized gradient
		//		for (PosNegRWExample x : examples) {
		////			this.learner.initializeFeatures(paramVec,x.getGraph());
		//			this.learner.accumulateGradient(paramVec, x, sumGradient);
		//			k++;
		//		}

		NamedThreadFactory parseThreads = new NamedThreadFactory("parse-");
		NamedThreadFactory gradThreads = new NamedThreadFactory("grad-");
		int nthreadsper = Math.max(this.nthreads/2, 1);
		ExecutorService parsePool, gradPool, cleanPool; 

		parsePool = Executors.newFixedThreadPool(nthreadsper, parseThreads);
		gradPool = Executors.newFixedThreadPool(nthreadsper, gradThreads);
		cleanPool = Executors.newSingleThreadExecutor();

		// run examples
		int id=1;
		int countdown=-1; AdaGradTrainer notify = null;
		for (String s : examples) {
			long queueSize = (((ThreadPoolExecutor) gradPool).getTaskCount()-((ThreadPoolExecutor) gradPool).getCompletedTaskCount());
			if (log.isDebugEnabled()) log.debug("Queue size "+queueSize);
			if (countdown>0) {
				if (log.isDebugEnabled()) log.debug("Countdown "+countdown);
				countdown--;
			} else if (countdown == 0) {
				if (log.isDebugEnabled()) log.debug("Countdown "+countdown +"; throttling:");
				countdown--;
				notify = null;
				try {
					synchronized(this) {
						if (log.isDebugEnabled()) log.debug("Clearing training queue...");
						while((((ThreadPoolExecutor) gradPool).getTaskCount()-((ThreadPoolExecutor) gradPool).getCompletedTaskCount()) > this.nthreads)
							this.wait();
						if (log.isDebugEnabled()) log.debug("Queue cleared.");
					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else if (queueSize > 1.5*this.nthreads) {
				if (log.isDebugEnabled()) log.debug("Starting countdown");
				countdown=this.nthreads;
				notify = this;
			}
			Future<PosNegRWExample> parsed = parsePool.submit(new Parse(s, builder, id));
			Future<Integer> gradfound = gradPool.submit(new Grad(parsed, paramVec, sumGradient, totSqGrad, agLearner, id, notify));
			cleanPool.submit(new TraceLosses(gradfound, id));
		}
		parsePool.shutdown();
		try {
			parsePool.awaitTermination(7,TimeUnit.DAYS);
			gradPool.shutdown();
			gradPool.awaitTermination(7, TimeUnit.DAYS);
			cleanPool.shutdown();
			cleanPool.awaitTermination(7, TimeUnit.DAYS);
		} catch (InterruptedException e) {
			log.error("Interrupted?",e);
		}

		this.agLearner.cleanupParams(paramVec, sumGradient);

		//WW: renormalize by the total number of queries
		for (Iterator<String> it = sumGradient.keySet().iterator(); it.hasNext(); ) {
			String feature = it.next();
			double unnormf = sumGradient.get(feature);
			// query count stored in numExamplesThisEpoch, as noted above
			double norm = unnormf / this.statistics.numExamplesThisEpoch;
			sumGradient.put(feature, norm);
		}

		return sumGradient;
	}

	/////////////////////// Multithreading scaffold ///////////////////////

	/**
	 * Transforms from inputs to outputs
	 * @author "Kathryn Mazaitis <krivard@cs.cmu.edu>"
	 *
	 */
	protected class AdaGradTrain implements Callable<Integer> {
		Future<PosNegRWExample> in;
		ParamVector paramVec;
		AdaGradSRW agLearner;
		SimpleParamVector<String> totSqGrad;
		int id;
		AdaGradTrainer notify;
		public AdaGradTrain(Future<PosNegRWExample> parsed, ParamVector paramVec, SimpleParamVector<String> totSqGrad, AdaGradSRW agLearner, int id, AdaGradTrainer notify) {
			this.in = parsed;
			this.id = id;
			this.agLearner = agLearner;
			this.totSqGrad = totSqGrad;
			this.paramVec = paramVec;
			this.notify = notify;
		}
		@Override
		public Integer call() throws Exception {
			PosNegRWExample ex = in.get();
			if (notify != null) synchronized(notify) { notify.notify(); }
			long start = System.currentTimeMillis();
			if (log.isDebugEnabled()) log.debug("Training start "+this.id);
			agLearner.trainOnExample(paramVec, totSqGrad, ex);
			if (log.isDebugEnabled()) log.debug("Training done "+this.id);
			statistics.updateTrainingStatistics(System.currentTimeMillis()-start);

			if (log.isDebugEnabled()){
				//rosecatherinek: testing P values at the end of training
				int[] posList = ex.getPosList();
				int[] negList = ex.getNegList();
				int[] seedList = ex.getQueryVec().keys();
				double[] p = ex.p;

				log.debug("P of pos examples: ");
				for(int i : posList){
					log.debug(p[i]);
				}
				log.debug("P of neg examples:");
				for(int i : negList){
					log.debug(p[i]);
				}
				log.debug("P of seeds:");
				for(int i : seedList){
					log.debug(i + ": " + p[i]);
				}
			}

			return ex.length();
		}
	}

	protected class Grad extends AdaGradTrain {
		ParamVector sumGradient;
		public Grad(Future<PosNegRWExample> parsed, ParamVector paramVec, ParamVector sumGradient, SimpleParamVector<String> totSqGrad, AdaGradSRW agLearner, int id, AdaGradTrainer notify) {
			super(parsed, paramVec, totSqGrad, agLearner, id, notify);
			this.sumGradient = sumGradient;
		}
		@Override
		public Integer call() throws Exception {
			PosNegRWExample ex = in.get();
			if (notify != null) synchronized(notify) { notify.notify(); }
			if (log.isDebugEnabled()) log.debug("Gradient start "+this.id);
			agLearner.accumulateGradient(paramVec, ex, sumGradient);
			if (log.isDebugEnabled()) log.debug("Gradient done "+this.id);
			return 1; 
			// ^^^^ this is the equivalent of k++ from before;
			// the total sum (query count) will be stored in numExamplesThisEpoch
			// by TraceLosses. It's a hack but it works
		}
	}


}
