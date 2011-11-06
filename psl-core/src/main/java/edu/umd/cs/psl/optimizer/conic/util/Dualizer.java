/*
 * This file is part of the PSL software.
 * Copyright 2011 University of Maryland
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
package edu.umd.cs.psl.optimizer.conic.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cern.colt.matrix.tdouble.DoubleMatrix1D;
import edu.umd.cs.psl.optimizer.conic.program.ConeType;
import edu.umd.cs.psl.optimizer.conic.program.ConicProgram;
import edu.umd.cs.psl.optimizer.conic.program.LinearConstraint;
import edu.umd.cs.psl.optimizer.conic.program.NonNegativeOrthantCone;
import edu.umd.cs.psl.optimizer.conic.program.SecondOrderCone;
import edu.umd.cs.psl.optimizer.conic.program.Variable;

public class Dualizer {
	
	private static final Logger log = LoggerFactory.getLogger(Dualizer.class);
	
	private boolean checkedOut;
	
	private static final ArrayList<ConeType> supportedCones = new ArrayList<ConeType>(2);
	static {
		supportedCones.add(ConeType.NonNegativeOrthantCone);
	}
	
	private ConicProgram primalProgram, dualProgram;
	private Map<Variable, LinearConstraint> primalVarsToDualCons;
	private Map<Variable, Variable> primalVarsToDualVars;
	private Map<LinearConstraint, Variable> primalConsToDualVars;
	private Map<LinearConstraint, SOCVariablePair> varPairs;
	
	public Dualizer(ConicProgram program) {
		primalProgram = program;
		checkedOut = false;
	}
	
	public static boolean supportsConeTypes(Collection<ConeType> types) {
		return supportedCones.containsAll(types);
	}
	
	public ConicProgram getDualProgram() {
		verifyCheckedOut();
		return dualProgram;
	}
	
	public void verifyCheckedOut() {
		if (!checkedOut)
			throw new IllegalStateException("Dual program is not checked out.");
	}
	
	public void verifyCheckedIn() {
		if (checkedOut)
			throw new IllegalStateException("Dual program is not checked in.");
	}
	
	public void checkOutProgram() {
		verifyCheckedIn();
		dualProgram = new ConicProgram();
		primalVarsToDualCons = new HashMap<Variable, LinearConstraint>();
		primalVarsToDualVars = new HashMap<Variable, Variable>();
		primalConsToDualVars = new HashMap<LinearConstraint, Variable>();
		varPairs = new HashMap<LinearConstraint, SOCVariablePair>();
		
		Variable slack, primalVar, dualVar;
		LinearConstraint dualCon;
		Double coeff, scaledValue;
		
		for (LinearConstraint con : primalProgram.getConstraints()) {
			slack = null;
			for (Variable slackCandidate : con.getVariables().keySet()) {
				if (slackCandidate.getLinearConstraints().size() == 1
						&& slackCandidate.getCone() instanceof NonNegativeOrthantCone
						&& slackCandidate.getObjectiveCoefficient() == 0.0) {
					slack = slackCandidate;
					break;
				}
			}
			
			/* If a slack variable is found, it will be represented as a dual slack variable */
			if (slack != null) {
				dualVar = dualProgram.createNonNegativeOrthantCone().getVariable();
				dualVar.setObjectiveCoefficient(con.getConstrainedValue());
				primalVarsToDualVars.put(slack, dualVar);
				primalConsToDualVars.put(con, dualVar);
			}
			/*
			 * Otherwise, the Lagrange multiplier of the primal constraint cannot, in
			 * general, be bounded at zero. Therefore, its value will be represented
			 * by the inner variable of a second-order cone.
			 */
			else {
				SOCVariablePair pair = new SOCVariablePair();
				SecondOrderCone cone = dualProgram.createSecondOrderCone(2);
				for (Variable v : cone.getVariables()) {
					if (cone.getNthVariable().equals(v))
						pair.inner = v;
				}
				pair.inner.setObjectiveCoefficient(con.getConstrainedValue());
				varPairs.put(con, pair);
			}
		}
		
		for (NonNegativeOrthantCone cone : primalProgram.getNonNegativeOrthantCones()) {
			primalVar = cone.getVariable();
			/*
			 * If the variable isn't marked as a slack variable, makes a constraint
			 * in the dualized program for it. Immediately creates a new variable
			 * to keep the new constraint's Lagrange multiplier non-negative (to
			 * match the primal variable in the primal program).
			 */
			if (primalVarsToDualVars.get(primalVar) == null) {
				dualCon = dualProgram.createConstraint();
				dualCon.setConstrainedValue(-1 * primalVar.getObjectiveCoefficient());
				primalVarsToDualCons.put(primalVar, dualCon);
				dualVar = dualProgram.createNonNegativeOrthantCone().getVariable();
				dualCon.addVariable(dualVar, -1.0);
				dualVar.setObjectiveCoefficient(0.0);
			}
		}
		
		/* Puts it all together */
		for (LinearConstraint con : primalProgram.getConstraints()) {
			dualVar = primalConsToDualVars.get(con);
			if (dualVar == null)
				dualVar = varPairs.get(con).inner;
			
			for (Map.Entry<Variable, Double> e : con.getVariables().entrySet()) {
				dualCon = primalVarsToDualCons.get(e.getKey());
				if (dualCon != null) {
					dualCon.addVariable(dualVar, e.getValue());
				}
			}
		}
		
		/*
		 * Scales and flips constraints in dual program to make slacks have
		 * coefficients of 1.
		 */
		for (Map.Entry<Variable, Variable> e : primalVarsToDualVars.entrySet()) {
			coeff = e.getKey().getLinearConstraints().iterator().next().getVariables().get(e.getKey());
			if (coeff != 1.0) {
				dualVar = e.getValue();
				dualVar.setObjectiveCoefficient(dualVar.getObjectiveCoefficient() / coeff);
				for (LinearConstraint con : new HashSet<LinearConstraint>(dualVar.getLinearConstraints())) {
					scaledValue = con.getVariables().get(dualVar) / coeff;
					con.removeVariable(dualVar);
					con.addVariable(dualVar, scaledValue);
				}
			}
		}
		
		checkedOut = true;
	}
	
	public void checkInProgram() {
		verifyCheckedOut();
		
		DoubleMatrix1D x = primalProgram.getX();
		
		for (Map.Entry<Variable, LinearConstraint> e : primalVarsToDualCons.entrySet()) {
			x.set(primalProgram.index(e.getKey()), e.getValue().getLagrange());
		}
		
		for (Map.Entry<Variable, Variable> e : primalVarsToDualVars.entrySet()) {
			x.set(primalProgram.index(e.getKey()), e.getValue().getDualValue());
		}
		
		checkedOut = false;
	}
	
	private class SOCVariablePair {
		private Variable inner;
	}
}
