package edu.cmu.ml.proppr.prove;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import com.skjegstad.utils.BloomFilter;

import edu.cmu.ml.proppr.learn.tools.Linear;
import edu.cmu.ml.proppr.learn.tools.SquashingFunction;
import edu.cmu.ml.proppr.prove.wam.Goal;
import edu.cmu.ml.proppr.prove.wam.Query;
import edu.cmu.ml.proppr.util.Dictionary;

/**
 * featureDictWeighter that weights each feature with a default
    value of 1.0, but allows a one to plug in a dictionary of
    non-default weights.
 * @author krivard
 *
 */
public class InnerProductWeighter extends FeatureDictWeighter {
	private static final int MAX_UNKNOWN_FEATURE_WARNINGS = 10;
	private int numUnknownFeatures = 0;
	private static final Logger log = Logger.getLogger(InnerProductWeighter.class);
	protected static final BloomFilter<Goal> unknownFeatures = new BloomFilter<Goal>(.01,100);
	private static SquashingFunction DEFAULT_SQUASHING_FUNCTION() {
		return new Linear();
	}
	public InnerProductWeighter() {
		this(new HashMap<Goal,Double>());
	}
	public InnerProductWeighter(Map<Goal,Double> weights) {
		this(DEFAULT_SQUASHING_FUNCTION(), weights);

	}
	public InnerProductWeighter(SquashingFunction f, Map<Goal,Double> weights) {
		super(f);
		this.weights = weights;
	}
	@Override
	public double w(Map<Goal, Double> featureDict) {
		// check for unknown features
		for (Goal g : featureDict.keySet()) {
			if (!this.weights.containsKey(g)) {
				if (!unknownFeatures.contains(g)) {
					if (numUnknownFeatures<MAX_UNKNOWN_FEATURE_WARNINGS) {
						log.warn("Using default weight 1.0 for unknown feature "+g+" (this message only prints once per feature)");				
					} else if (numUnknownFeatures==MAX_UNKNOWN_FEATURE_WARNINGS) {
						log.warn("You won't get warnings about other unknown features");
					}
					unknownFeatures.add(g);
					numUnknownFeatures++;
				}
			}
		}
		return this.squashingFunction.edgeWeight(this.weights, featureDict);
	}
	public static FeatureDictWeighter fromParamVec(Map<String, Double> paramVec) {
		return fromParamVec(paramVec, DEFAULT_SQUASHING_FUNCTION());
	}
	public static InnerProductWeighter fromParamVec(Map<String, Double> paramVec, SquashingFunction f) {
		Map<Goal,Double> weights = new HashMap<Goal,Double>();
		for (Map.Entry<String,Double> s : paramVec.entrySet()) {
			weights.put(Query.parseGoal(s.getKey()), s.getValue());
		}
		return new InnerProductWeighter(f, weights);
	}
	public Map<Goal,Double> getWeights() {
		return this.weights;
	}
}