package de.tudarmstadt.ukp.similarity.semeval2013;

import static org.uimafit.factory.AnalysisEngineFactory.createPrimitive;
import static org.uimafit.factory.AnalysisEngineFactory.createPrimitiveDescription;
import static org.uimafit.factory.CollectionReaderFactory.createCollectionReader;
import static org.uimafit.factory.ExternalResourceFactory.createExternalResourceDescription;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.apache.commons.io.FileUtils;
import org.apache.commons.math.stat.correlation.PearsonsCorrelation;
import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.collection.CollectionReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.uimafit.factory.AggregateBuilder;
import org.uimafit.pipeline.SimplePipeline;

import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.meta.FilteredClassifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AddClassification;
import weka.filters.unsupervised.attribute.AddID;
import weka.filters.unsupervised.attribute.Remove;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Document;
import de.tudarmstadt.ukp.dkpro.core.tokit.BreakIteratorSegmenter;
import de.tudarmstadt.ukp.similarity.dkpro.annotator.SimilarityScorer;
import de.tudarmstadt.ukp.similarity.dkpro.io.CombinationReader;
import de.tudarmstadt.ukp.similarity.dkpro.io.SemEvalCorpusReader;
import de.tudarmstadt.ukp.similarity.dkpro.io.ShortAnswerGradingReader;
import de.tudarmstadt.ukp.similarity.dkpro.io.CombinationReader.CombinationStrategy;
import de.tudarmstadt.ukp.similarity.dkpro.resource.ml.LinearRegressionResource;
import de.tudarmstadt.ukp.similarity.ml.io.SimilarityScoreWriter;
import de.tudarmstadt.ukp.similarity.semeval2013.SemEval2013Baseline.Dataset;
import de.tudarmstadt.ukp.similarity.semeval2013.SemEval2013Baseline.EvaluationMetric;
import de.tudarmstadt.ukp.similarity.semeval2013.SemEval2013Baseline.Mode;
import de.tudarmstadt.ukp.statistics.correlation.PearsonCorrelation;

import static de.tudarmstadt.ukp.similarity.semeval2013.SemEval2013Baseline.OUTPUT_DIR;
import static de.tudarmstadt.ukp.similarity.semeval2013.SemEval2013Baseline.MODELS_DIR;
import static de.tudarmstadt.ukp.similarity.semeval2013.SemEval2013Baseline.REPORT_FILE;


public class Evaluator
{
	public static final String LF = System.getProperty("line.separator");
	
	public static void runLinearRegression(Dataset train, Dataset... test)
		throws UIMAException, IOException
	{
		for (Dataset dataset : test)
		{
			CollectionReader reader = createCollectionReader(SemEvalCorpusReader.class,
					SemEvalCorpusReader.PARAM_INPUT_FILE, "classpath:/datasets/semeval/test/STS.input." + dataset.toString() + ".txt",
					SemEvalCorpusReader.PARAM_COMBINATION_STRATEGY, CombinationStrategy.SAME_ROW_ONLY.toString());
			
			AnalysisEngineDescription seg = createPrimitiveDescription(BreakIteratorSegmenter.class);
			
			AggregateBuilder builder = new AggregateBuilder();
			builder.add(seg, CombinationReader.INITIAL_VIEW, CombinationReader.VIEW_1);
			builder.add(seg, CombinationReader.INITIAL_VIEW, CombinationReader.VIEW_2);
			AnalysisEngine aggr_seg = builder.createAggregate();
	
			AnalysisEngine scorer = createPrimitive(SimilarityScorer.class,
				    SimilarityScorer.PARAM_NAME_VIEW_1, CombinationReader.VIEW_1,
				    SimilarityScorer.PARAM_NAME_VIEW_2, CombinationReader.VIEW_2,
				    SimilarityScorer.PARAM_SEGMENT_FEATURE_PATH, Document.class.getName(),
				    SimilarityScorer.PARAM_TEXT_SIMILARITY_RESOURCE, createExternalResourceDescription(
				    	LinearRegressionResource.class,
				    	LinearRegressionResource.PARAM_TRAIN_ARFF, MODELS_DIR + "/train/" + train.toString() + ".arff",
				    	LinearRegressionResource.PARAM_TEST_ARFF, MODELS_DIR + "/test/" + dataset.toString() + ".arff")
				    );
			
			AnalysisEngine writer = createPrimitive(SimilarityScoreWriter.class,
					SimilarityScoreWriter.PARAM_OUTPUT_FILE, OUTPUT_DIR + "/test/" + dataset.toString() + ".csv",
					SimilarityScoreWriter.PARAM_OUTPUT_SCORES_ONLY, true,
					SimilarityScoreWriter.PARAM_OUTPUT_GOLD_SCORES, false);
	
			SimplePipeline.runPipeline(reader, aggr_seg, scorer, writer);
		}
	}
	
	public static void runLinearRegressionCV(Mode mode, Dataset... datasets)
		throws Exception
	{
		for (Dataset dataset : datasets)
		{
			// Set parameters
			int folds = 10;
			Classifier baseClassifier = new LinearRegression();
			
			// Set up the random number generator
	    	long seed = new Date().getTime();			
			Random random = new Random(seed);	
	    	
			// Add IDs to the instances
			AddID.main(new String[] {"-i", MODELS_DIR + "/" + mode.toString().toLowerCase() + "/" + dataset.toString() + ".arff",
 								 	 "-o", MODELS_DIR + "/" + mode.toString().toLowerCase() + "/" + dataset.toString() + "-plusIDs.arff" });
			Instances data = DataSource.read(MODELS_DIR + "/" + mode.toString().toLowerCase() + "/" + dataset.toString() + "-plusIDs.arff");
			data.setClassIndex(data.numAttributes() - 1);				
			
	        // Instantiate the Remove filter
	        Remove removeIDFilter = new Remove();
        	removeIDFilter.setAttributeIndices("first");
			
			// Randomize the data
			data.randomize(random);
		
			// Perform cross-validation
		    Instances predictedData = null;
		    Evaluation eval = new Evaluation(data);
		    
		    for (int n = 0; n < folds; n++)
		    {
		    	Instances train = data.trainCV(folds, n, random);
		        Instances test = data.testCV(folds, n);
		        
		        // Copy the classifier
		        Classifier classifier = AbstractClassifier.makeCopy(baseClassifier);
		        	         		        
		        // Instantiate the FilteredClassifier
		        FilteredClassifier filteredClassifier = new FilteredClassifier();
		        filteredClassifier.setFilter(removeIDFilter);
		        filteredClassifier.setClassifier(classifier);
		        	 
		        // Build the classifier
		        filteredClassifier.buildClassifier(train);
		         
		        // Evaluate
		        eval.evaluateModel(classifier, test);
		        
		        // Add predictions
		        AddClassification filter = new AddClassification();
		        filter.setClassifier(classifier);
		        filter.setOutputClassification(true);
		        filter.setOutputDistribution(false);
		        filter.setOutputErrorFlag(true);
		        filter.setInputFormat(train);
		        Filter.useFilter(train, filter);  // trains the classifier
		        
		        Instances pred = Filter.useFilter(test, filter);  // performs predictions on test set
		        if (predictedData == null)
		        	predictedData = new Instances(pred, 0);
		        for (int j = 0; j < pred.numInstances(); j++)
		        	predictedData.add(pred.instance(j));		        
		    }
		    
		    // Prepare output scores
		    double[] scores = new double[predictedData.numInstances()];
		    
		    for (Instance predInst : predictedData)
		    {
		    	int id = new Double(predInst.value(predInst.attribute(0))).intValue() - 1;
		    	
		    	int valueIdx = predictedData.numAttributes() - 2;
		    	
		    	double value = predInst.value(predInst.attribute(valueIdx));
		    	
		    	scores[id] = value;
		    	
		    	// Limit to interval [0;5]
				if (scores[id] > 5.0) 	scores[id] = 5.0;
				if (scores[id] < 0.0)	scores[id] = 0.0;
		    }
		    
		    // Output
		    StringBuilder sb = new StringBuilder();
		    for (Double score : scores)
		    	sb.append(score.toString() + LF);
		    
		    FileUtils.writeStringToFile(
		    	new File(OUTPUT_DIR + "/" + mode.toString().toLowerCase() + "/" + dataset.toString() + ".csv"),
		    	sb.toString());
		}
	}
	
	public static void runEvaluationMetrics(Mode mode, EvaluationMetric... metrics)
		throws IOException
	{
		StringBuilder sb = new StringBuilder();
		
		for (EvaluationMetric metric : metrics)
		{
			for (Dataset dataset : Dataset.values())
			{
				if (dataset.equals(Dataset.ALL))
					continue;
				
				File expScoresFile = new File(OUTPUT_DIR + "/" + mode.toString().toLowerCase() + "/" + dataset.toString() + ".csv");
				
				if (!expScoresFile.exists())
					continue;
				
				String gsScoresFilePath = "classpath:/goldstandards/semeval/" + mode.toString().toLowerCase() + "/" + 
						"STS.gs." + dataset.toString() + ".txt";
				
				PathMatchingResourcePatternResolver r = new PathMatchingResourcePatternResolver();
		        Resource res = r.getResource(gsScoresFilePath);				
				File gsScoresFile = res.getFile();
				
				List<Double> expScores = new ArrayList<Double>();
				List<Double> gsScores = new ArrayList<Double>();
				
				List<String> expLines = FileUtils.readLines(expScoresFile);
				List<String> gsLines = FileUtils.readLines(gsScoresFile);
				
				for (int i = 0; i < expLines.size(); i++)
				{
					expScores.add(Double.parseDouble(expLines.get(i)));
					gsScores.add(Double.parseDouble(gsLines.get(i)));
				}

				Double correl = PearsonCorrelation.computeCorrelation(expScores, gsScores);
				
				FileUtils.writeStringToFile(
						new File(OUTPUT_DIR + "/" + mode.toString().toLowerCase() + "/" + dataset.toString() + ".txt"),
						correl.toString());
			}
		}
		
		FileUtils.writeStringToFile(new File(OUTPUT_DIR + "/" + REPORT_FILE), sb.toString());
	}
}