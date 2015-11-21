/*
 * ARX: Powerful Data Anonymization
 * Copyright 2012 - 2015 Florian Kohlmayer, Fabian Prasser
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.deidentifier.arx.aggregates;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.mahout.classifier.sgd.L1;
import org.apache.mahout.classifier.sgd.OnlineLogisticRegression;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.vectorizer.encoders.ConstantValueEncoder;
import org.apache.mahout.vectorizer.encoders.StaticWordValueEncoder;
import org.deidentifier.arx.DataHandleStatistics;
import org.deidentifier.arx.DataType;
import org.deidentifier.arx.DataType.ARXDate;
import org.deidentifier.arx.DataType.ARXDecimal;
import org.deidentifier.arx.DataType.ARXInteger;
import org.deidentifier.arx.common.WrappedBoolean;
import org.deidentifier.arx.exceptions.ComputationInterruptedException;

/**
 * Statistics representing the prediction accuracy of a data mining
 * classification operator
 * 
 * @author Fabian Prasser
 */
public class StatisticsClassification {

    /** Features and class: last element is the class */
    private final int[]          indexes;
    /** Data types */
    private final DataType<?>[]  type;
    /** Minimum */
    private final double[]       minimum;
    /** Maximum */
    private final double[]       maximum;
    /** Cardinalities */
    private final int[]          cardinality;
    /** Interrupt flag */
    private final WrappedBoolean interrupt;
    /** Random */
    private final Random         random;
    /** Result */
    private double               accuracy;

    /**
     * Creates a new set of statistics for the given classification task
     * @param handle - The handle
     * @param features - The feature attributes
     * @param clazz - The class attributes
     * @param seed - The random seed, null, if the process should be randomized
     * @param samplingFraction - The sampling fraction
     * @throws ParseException 
     */
    StatisticsClassification(DataHandleStatistics handle,
                             String[] features,
                             String clazz,
                             Integer seed,
                             double samplingFraction,
                             WrappedBoolean interrupt) throws ParseException {

        // Init
        this.interrupt = interrupt;
        
        // Check and clean up
        if (samplingFraction <= 0d || samplingFraction > 1d) {
            throw new IllegalArgumentException("Samling fraction must be in ]0,1]");
        }
        if (clazz == null) {
            throw new IllegalArgumentException("No class attribute defined");
        }
        if (handle.getColumnIndexOf(clazz) == -1) {
            throw new IllegalArgumentException("Unknown class '"+clazz+"'");
        }
        if (features == null) {
            throw new IllegalArgumentException("No features defined");
        }
        List<String> featuresList = new ArrayList<>();
        for (String feature : features) {
            if (feature == null) {
                throw new IllegalArgumentException("Feature must not be null");    
            }
            if (handle.getColumnIndexOf(feature) == -1) {
                throw new IllegalArgumentException("Unknown feature '"+feature+"'");
            }
            if (!feature.equals(clazz)) {
                featuresList.add(feature);
            }
        }
        features = featuresList.toArray(new String[featuresList.size()]);
        
        // Initialize random
        if (seed == null) {
            this.random = new Random();
        } else {
            this.random = new Random(seed);
        }
        
        // Create indexes
        this.indexes = getAttributeIndexes(handle, features, clazz);

        // Map for the target attribute
        Map<String, Integer> map = new HashMap<String, Integer>();
        
        // Obtain meta data
        type = new DataType[indexes.length];
        minimum = new double[indexes.length];
        maximum = new double[indexes.length];
        cardinality = new int[indexes.length];
        
        // For each attribute
        for (int index = 0; index < indexes.length; index++) {
            
            // Obtain
            int column = indexes[index];
            String attribute = handle.getAttributeName(column);
            String[] values = handle.getDistinctValues(column);

            // Store
            type[index] = handle.getDataType(attribute);
            cardinality[index] = values.length;
            
            // Create map for class attribute
            if (index == indexes.length - 1) {
                for (int i = 0; i < values.length; i++) {
                    
                    // Check
                    checkInterrupt();
                    
                    // Do
                    map.put(values[i], i);
                }
                
            // Compute min and max for others
            } else {
                // Numeric
                if (type[index] instanceof ARXDecimal ||
                    type[index] instanceof ARXDecimal ||
                    type[index] instanceof ARXDecimal) {
                    
                    // Compute minimum and maximum for feature scaling
                    minimum[index] = Double.MAX_VALUE;
                    maximum[index] = - Double.MAX_VALUE;
                    for (String value : values) {
                        
                        // Check
                        checkInterrupt();
                        
                        // Do
                        double numericValue = 0d;
                        if (type[index] instanceof ARXDecimal) {
                            numericValue = (Double)type[index].parse(value);
                        } else if (type[index] instanceof ARXInteger) {
                            numericValue = (Long)type[index].parse(value);
                        } else if (type[index] instanceof ARXDate) {
                            numericValue = ((Date)type[index].parse(value)).getTime();
                        }
                        minimum[index] = Math.min(minimum[index], numericValue);
                        maximum[index] = Math.max(maximum[index], numericValue);
                    }
                    
                // Ordinal or nominal
                } else {
                    minimum[index] = 0d;
                    maximum[index] = 0d;
                }   
            }
        }
        
        // Train and cross validate
        int k = handle.getNumRows() > 10 ? 10 : handle.getNumRows();
        this.accuracy = getAccuracyAccordingToKFoldCrossValidation(handle, map, k, random);
    }

    /**
     * Returns the accuracy of the classifier
     */
    public double getAccuracy() {
        return accuracy;
    }

    /**
     * Checks whether an interruption happened.
     */
    private void checkInterrupt() {
        if (interrupt.value) {
            throw new ComputationInterruptedException("Interrupted");
        }
    }

    /**
     * Performs k-fold cross validation
     * @param handle
     * @param map
     * @param k
     * @param random
     * @return
     * @throws ParseException 
     */
    private double getAccuracyAccordingToKFoldCrossValidation(DataHandleStatistics handle, 
                                               Map<String, Integer> map, 
                                               int k,
                                               Random random) throws ParseException {

        // Prepare encoders
        ConstantValueEncoder interceptEncoder = new ConstantValueEncoder("intercept");
        StaticWordValueEncoder featureEncoder = new StaticWordValueEncoder("feature");

        // Prepare indexes
        List<Integer> rows = new ArrayList<>();
        for (int row = 0; row < handle.getNumRows(); row++) {
            rows.add(row);
        }
        Collections.shuffle(rows, random);
        
        // Create folds
        List<List<Integer>> folds = new ArrayList<>();
        int size = handle.getNumRows() / k;
        size = size > 1 ? size : 1;
        for (int i = 0; i < k; i++) {
            
            // Check
            checkInterrupt();
            
            // For each fold
            int min = i * size;
            int max = (i + 1) * size;
            if (i == k - 1) {
                max = handle.getNumRows();
            }
            
            // Collect rows
            List<Integer> fold = new ArrayList<>();
            for (int j = min; j < max; j++) {
                fold.add(rows.get(j));
            }
            
            // Store
            folds.add(fold);
        }
        
        // Free
        rows.clear();
        rows = null;
        
        // Perform cross validation
        double correct = 0d;
        double total = 0d;
        
        // For each fold as a validation set
        for (int i = 0; i < folds.size(); i++) {
            
            // Prepare classifier
            int features = indexes.length - 1 > 0 ? indexes.length - 1 : 0;
            OnlineLogisticRegression lr = new OnlineLogisticRegression(map.size(), // Classes
                                                                       features, // Features
                                                                       new L1()); // Function
            
            // Configure
            lr.learningRate(1);
            lr.alpha(1);
            lr.lambda(0.000001);
            lr.stepOffset(10000);
            lr.decayExponent(0.2);
            
            // For all training sets
            for (int j = 0; j < folds.size(); j++) {
                if (j != i) {
                    List<Integer> trainingset = folds.get(i);
                    for (int row : trainingset) {

                        // Check
                        checkInterrupt();
                        
                        // Train
                        lr.train(getClass(handle, row, map), 
                                 getFeatures(handle, row, interceptEncoder, featureEncoder));
                    }
                }
            }
            
            // Now validate
            // How is AUC computed for multinomial logistic regression?
            // It seems that the one-vs.-rest strategy is the solution
            // Auc eval = new Auc(0.5);
            List<Integer> validationset = folds.get(i);
            for (int row : validationset) {

                // Check
                checkInterrupt();
                
                // Count
                total ++;
                correct += getClass(handle, row, map) == lr.classifyFull(getFeatures(handle, row, interceptEncoder, featureEncoder)).maxValueIndex() ? 1 : 0;
                
                // Validate
                // eval.add(map.get(handle.getValue(row, indexes[indexes.length-1])), 
                // getMostProbableValue(lr.classify(getFeatures(handle, row, interceptEncoder, featureEncoder))));
            }
            
            // Store
            //accuracy += eval.auc();
            
            // Close
            lr.close();
        }
        
        // Return mean
        return correct / total;
    }
    
    /**
     * Returns the indexes of all relevant attributes
     * @param handle
     * @param features
     * @param clazz
     * @return
     */
    private int[] getAttributeIndexes(DataHandleStatistics handle, String[] features, String clazz) {
        // Collect
        List<Integer> list = new ArrayList<>();
        for (int column = 0; column < handle.getNumColumns(); column++) {
            String attribute = handle.getAttributeName(column);
            if (isContained(features, attribute)) {
                list.add(column);
            }
        }
        list.add(handle.getColumnIndexOf(clazz));
        
        // Convert
        int[] result = new int[list.size()];
        for (int i=0; i<list.size(); i++) {
            result[i] = list.get(i);
        }
        
        // Return
        return result;
    }

    /**
     * Returns the class for the given row
     * @param handle
     * @param row
     * @param map
     * @return
     */
    private int getClass(DataHandleStatistics handle, int row, Map<String, Integer> map) {
        return map.get(handle.getValue(row, indexes[indexes.length - 1]));
    }

    /**
     * Returns the feature vector for the given row
     * @param handle
     * @param row
     * @param interceptEncoder
     * @param featureEncoder
     * @return
     * @throws ParseException
     */
    private Vector getFeatures(DataHandleStatistics handle, int row,
                               ConstantValueEncoder interceptEncoder,
                               StaticWordValueEncoder featureEncoder) throws ParseException {
        
        // Prepare
        int length = this.indexes.length - 1;
        DenseVector vector = new DenseVector(length != 0 ? length : 1);
        interceptEncoder.addToVector("1", vector);
        
        // Special case where there are no features
        if (length == 0) {
            featureEncoder.addToVector("Feature", 1, vector);
            return vector;
        }
        
        // For each attribute
        for (int index = 0; index < length; index++) {
            
            // Obtain data
            int column = indexes[index];
            String name = handle.getAttributeName(column);
            DataType<?> type = this.type[index];
            double minimum = this.minimum[index];
            double maximum = this.maximum[index];
            
            // Set value
            if (type instanceof ARXDecimal) {
                featureEncoder.addToVector(name, (handle.getDouble(row, column) - minimum) / (maximum - minimum), vector);
            } else if (type instanceof ARXInteger) {
                featureEncoder.addToVector(name, (handle.getDouble(row, column) - minimum) / (maximum - minimum), vector);
            } else if (type instanceof ARXDate) {
                featureEncoder.addToVector(name, ((long)handle.getDate(row, column).getTime() - minimum) / (maximum - minimum), vector);
            } else {
                featureEncoder.addToVector(name + ":" + handle.getValue(row, column), 1, vector);
            }
        }
        
        // Return
        return vector;
    }

    /**
     * Returns whether the given array contains the given value
     * @param array
     * @param value
     * @return
     */
    private boolean isContained(String[] array, String value) {
        for (String element : array) {
            if (element.equals(value)) {
                return true;
            }
        }
        return false;
    }
}