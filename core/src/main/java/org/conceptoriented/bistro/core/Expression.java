package org.conceptoriented.bistro.core;

import java.util.List;

/**
 * This class combines one evaluator with the specification where its parameters have to be read from as an array of column paths.
 * The column paths allow for applying the same evaluator to different columns.
 */
public interface Expression extends Evaluator {

	public void setParameterPaths(List<ColumnPath> paths);
	public List<ColumnPath> getParameterPaths();

	/**
	 * Each parameter has a description which can be retrieved by means of this method. 
	 * It is not the best approach because these descriptions are language specific.
	 */
	//public List<String> getParamDescriptions();
}
