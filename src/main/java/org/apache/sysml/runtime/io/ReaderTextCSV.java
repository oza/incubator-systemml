/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;

import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.matrix.CSVReblockMR;
import org.apache.sysml.runtime.matrix.data.CSVFileFormatProperties;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.util.UtilFunctions;

public class ReaderTextCSV extends MatrixReader
{

	private CSVFileFormatProperties _props = null;
	
	public ReaderTextCSV(CSVFileFormatProperties props)
	{
		_props = props;
	}
	

	@Override
	public MatrixBlock readMatrixFromHDFS(String fname, long rlen, long clen, int brlen, int bclen, long estnnz) 
		throws IOException, DMLRuntimeException 
	{
		//allocate output matrix block
		MatrixBlock ret = null;
		if( rlen>0 && clen>0 ) //otherwise CSV reblock based on file size for matrix w/ unknown dimensions
			ret = createOutputMatrixBlock(rlen, clen, estnnz, true, false);
		
		//prepare file access
		JobConf job = new JobConf(ConfigurationManager.getCachedJobConf());	
		FileSystem fs = FileSystem.get(job);
		Path path = new Path( fname );
		
		//check existence and non-empty file
		checkValidInputFile(fs, path); 
	
		//core read 
		ret = readCSVMatrixFromHDFS(path, job, fs, ret, rlen, clen, brlen, bclen, 
				   _props.hasHeader(), _props.getDelim(), _props.isFill(), _props.getFillValue() );
		
		//finally check if change of sparse/dense block representation required
		//(nnz explicitly maintained during read)
		ret.examSparsity();
		
		return ret;
	}

	/**
	 * 
	 * @param path
	 * @param job
	 * @param fs
	 * @param dest
	 * @param rlen
	 * @param clen
	 * @param brlen
	 * @param bclen
	 * @param hasHeader
	 * @param delim
	 * @param fill
	 * @param fillValue
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	private MatrixBlock readCSVMatrixFromHDFS( Path path, JobConf job, FileSystem fs, MatrixBlock dest, 
			long rlen, long clen, int brlen, int bclen, boolean hasHeader, String delim, boolean fill, double fillValue )
		throws IOException
	{
		ArrayList<Path> files=new ArrayList<Path>();
		if(fs.isDirectory(path)) {
			for(FileStatus stat: fs.listStatus(path, CSVReblockMR.hiddenFileFilter))
				files.add(stat.getPath());
			Collections.sort(files);
		}
		else
			files.add(path);
		
		if ( dest == null ) {
			dest = computeCSVSize(files, job, fs, hasHeader, delim, fill, fillValue);
			clen = dest.getNumColumns();
		}
		
		boolean sparse = dest.isInSparseFormat();
		
		/////////////////////////////////////////
		String value = null;
		int row = 0;
		int col = -1;
		double cellValue = 0;
		long lnnz = 0;
		
		for(int fileNo=0; fileNo<files.size(); fileNo++)
		{
			BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(files.get(fileNo))));
			if(fileNo==0 && hasHeader ) 
				br.readLine(); //ignore header
			
			// Read the data
			boolean emptyValuesFound = false;
			try
			{
				if( sparse ) //SPARSE<-value
				{
					while( (value=br.readLine())!=null ) //foreach line
					{
						String cellStr = value.toString().trim();
						emptyValuesFound = false;
						String[] parts = IOUtilFunctions.split(cellStr, delim);
						col = 0;
						
						for(String part : parts) //foreach cell
						{
							part = part.trim();
							if ( part.isEmpty() ) {
								emptyValuesFound = true;
								cellValue = fillValue;
							}
							else {
								cellValue = UtilFunctions.parseToDouble(part);
							}
							if ( cellValue != 0 ) {
								dest.appendValue(row, col, cellValue);
								lnnz++;
							}
							col++;
						}
						
						//sanity checks for empty values and number of columns
						IOUtilFunctions.checkAndRaiseErrorCSVEmptyField(cellStr, fill, emptyValuesFound);
						IOUtilFunctions.checkAndRaiseErrorCSVNumColumns(path.toString(), cellStr, parts, clen);
						row++;
					}
				} 
				else //DENSE<-value
				{
					while( (value=br.readLine())!=null ) //foreach line
					{
						String cellStr = value.toString().trim();
						emptyValuesFound = false;
						String[] parts = IOUtilFunctions.split(cellStr, delim);
						col = 0;
						
						for( String part : parts ) //foreach cell
						{
							part = part.trim();
							if ( part.isEmpty() ) {
								emptyValuesFound = true;
								cellValue = fillValue;
							}
							else {
								cellValue = UtilFunctions.parseToDouble(part);
							}
							if ( cellValue != 0 ) {
								dest.setValueDenseUnsafe(row, col, cellValue);
								lnnz++;
							}
							col++;
						}
						
						//sanity checks for empty values and number of columns
						IOUtilFunctions.checkAndRaiseErrorCSVEmptyField(cellStr, fill, emptyValuesFound);
						IOUtilFunctions.checkAndRaiseErrorCSVNumColumns(path.toString(), cellStr, parts, clen);
						row++;
					}
				}
			}
			finally {
				IOUtilFunctions.closeSilently(br);
			}
		}
		
		//post processing
		dest.setNonZeros( lnnz );
		
		return dest;
	}
	
	/**
	 * 
	 * @param files
	 * @param job
	 * @param fs
	 * @param hasHeader
	 * @param delim
	 * @param fill
	 * @param fillValue
	 * @return
	 * @throws IOException
	 */
	private MatrixBlock computeCSVSize ( List<Path> files, JobConf job, FileSystem fs, boolean hasHeader, String delim, boolean fill, double fillValue) 
		throws IOException 
	{		
		int nrow = -1;
		int ncol = -1;
		String value = null;
		
		String cellStr = null;
		for(int fileNo=0; fileNo<files.size(); fileNo++)
		{
			BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(files.get(fileNo))));	
			try
			{
				// Read the header line, if there is one.
				if(fileNo==0)
				{
					if ( hasHeader ) 
						br.readLine(); //ignore header
					if( (value = br.readLine()) != null ) {
						cellStr = value.toString().trim();
						ncol = StringUtils.countMatches(cellStr, delim) + 1;
						nrow = 1;
					}
				}
				
				while ( (value = br.readLine()) != null ) {
					nrow++;
				}
			}
			finally {
				IOUtilFunctions.closeSilently(br);
			}
		}
		
		//create new matrix block (assume sparse for consistency w/ compiler)
		return new MatrixBlock(nrow, ncol, true);
	}
}
