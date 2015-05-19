/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.optimizer.lineage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.exec.ColumnInfo;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.ReduceSinkOperator;
import org.apache.hadoop.hive.ql.exec.RowSchema;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.hooks.LineageInfo;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.BaseColumnInfo;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.Dependency;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.DependencyType;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.Predicate;
import org.apache.hadoop.hive.ql.hooks.LineageInfo.TableAliasInfo;
import org.apache.hadoop.hive.ql.lib.DefaultGraphWalker;
import org.apache.hadoop.hive.ql.lib.DefaultRuleDispatcher;
import org.apache.hadoop.hive.ql.lib.Dispatcher;
import org.apache.hadoop.hive.ql.lib.GraphWalker;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.lib.Rule;
import org.apache.hadoop.hive.ql.lib.RuleRegExp;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.parse.RowResolver;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.ExprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeConstantDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeFieldDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeGenericFuncDesc;
import org.apache.hadoop.hive.ql.plan.ExprNodeNullDesc;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;

/**
 * Expression processor factory for lineage. Each processor is responsible to
 * create the leaf level column info objects that the expression depends upon
 * and also generates a string representation of the expression.
 */
public class ExprProcFactory {

  /**
   * Processor for column expressions.
   */
  public static class ColumnExprProcessor implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {

      ExprNodeColumnDesc cd = (ExprNodeColumnDesc) nd;
      ExprProcCtx epc = (ExprProcCtx) procCtx;

      // assert that the input operator is not null as there are no
      // exprs associated with table scans.
      Operator<? extends OperatorDesc> operator = epc.getInputOperator();
      assert (operator != null);

      RowResolver resolver = epc.getResolver();
      String[] nm = resolver.reverseLookup(cd.getColumn());
      if (nm == null && operator instanceof ReduceSinkOperator) {
        nm = resolver.reverseLookup(Utilities.removeValueTag(cd.getColumn()));
      }
      ColumnInfo ci = nm != null ? resolver.get(nm[0], nm[1]): null;

      // Insert the dependencies of inp_ci to that of the current operator, ci
      LineageCtx lc = epc.getLineageCtx();
      Dependency dep = lc.getIndex().getDependency(operator, ci);

      return dep;
    }

  }

  /**
   * Processor for any function or field expression.
   */
  public static class GenericExprProcessor implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {

      assert (nd instanceof ExprNodeGenericFuncDesc || nd instanceof ExprNodeFieldDesc);

      // Concatenate the dependencies of all the children to compute the new
      // dependency.
      Dependency dep = new Dependency();

      LinkedHashSet<BaseColumnInfo> bci_set = new LinkedHashSet<BaseColumnInfo>();
      LineageInfo.DependencyType new_type = LineageInfo.DependencyType.EXPRESSION;

      for (Object child : nodeOutputs) {
        if (child == null) {
          continue;
        }

        Dependency child_dep = (Dependency) child;
        new_type = LineageCtx.getNewDependencyType(child_dep.getType(), new_type);
        bci_set.addAll(child_dep.getBaseCols());
      }

      dep.setBaseCols(new ArrayList<BaseColumnInfo>(bci_set));
      dep.setType(new_type);

      return dep;
    }

  }

  /**
   * Processor for constants and null expressions. For such expressions the
   * processor simply returns a null dependency vector.
   */
  public static class DefaultExprProcessor implements NodeProcessor {

    @Override
    public Object process(Node nd, Stack<Node> stack, NodeProcessorCtx procCtx,
        Object... nodeOutputs) throws SemanticException {
      assert (nd instanceof ExprNodeConstantDesc || nd instanceof ExprNodeNullDesc);

      // Create a dependency that has no basecols
      Dependency dep = new Dependency();
      dep.setType(LineageInfo.DependencyType.SIMPLE);
      dep.setBaseCols(new ArrayList<BaseColumnInfo>());
      return dep;
    }
  }

  public static NodeProcessor getDefaultExprProcessor() {
    return new DefaultExprProcessor();
  }

  public static NodeProcessor getGenericFuncProcessor() {
    return new GenericExprProcessor();
  }

  public static NodeProcessor getFieldProcessor() {
    return new GenericExprProcessor();
  }

  public static NodeProcessor getColumnProcessor() {
    return new ColumnExprProcessor();
  }

  private static boolean findSourceColumn(
      LineageCtx lctx, Predicate cond, String tabAlias, String alias) {
    for (Map.Entry<String, Operator<? extends OperatorDesc>> topOpMap: lctx
        .getParseCtx().getTopOps().entrySet()) {
      Operator<? extends OperatorDesc> topOp = topOpMap.getValue();
      if (topOp instanceof TableScanOperator) {
        TableScanOperator tableScanOp = (TableScanOperator) topOp;
        Table tbl = tableScanOp.getConf().getTableMetadata();
        if (tbl.getTableName().equals(tabAlias)
            || tabAlias.equals(tableScanOp.getConf().getAlias())) {
          for (FieldSchema column: tbl.getCols()) {
            if (column.getName().equals(alias)) {
              TableAliasInfo table = new TableAliasInfo();
              table.setTable(tbl.getTTable());
              table.setAlias(tabAlias);
              BaseColumnInfo colInfo = new BaseColumnInfo();
              colInfo.setColumn(column);
              colInfo.setTabAlias(table);
              cond.getBaseCols().add(colInfo);
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * Get the expression string of an expression node.
   */
  public static String getExprString(RowSchema rs, ExprNodeDesc expr,
      LineageCtx lctx, Operator<? extends OperatorDesc> inpOp, Predicate cond) {
    if (expr instanceof ExprNodeColumnDesc) {
      ExprNodeColumnDesc col = (ExprNodeColumnDesc) expr;
      String columnName = col.getColumn();
      ColumnInfo ci = rs.getColumnInfo(columnName);
      String alias = ci != null ? ci.getAlias() : columnName;
      String internalName = ci != null ? ci.getInternalName() : columnName;
      Dependency dep = lctx.getIndex().getDependency(inpOp, internalName);
      String tabAlias = ci != null ? ci.getTabAlias() : col.getTabAlias();
      if ((tabAlias == null || tabAlias.startsWith("_") || tabAlias.startsWith("$"))
          && (dep != null && dep.getType() == DependencyType.SIMPLE)) {
        List<BaseColumnInfo> baseCols = dep.getBaseCols();
        if (baseCols != null && !baseCols.isEmpty()) {
          BaseColumnInfo baseCol = baseCols.get(0);
          tabAlias = baseCol.getTabAlias().getAlias();
          alias = baseCol.getColumn().getName();
        }
      }
      if (tabAlias != null && tabAlias.length() > 0
          && !tabAlias.startsWith("_") && !tabAlias.startsWith("$")) {
        if (cond != null && !findSourceColumn(lctx, cond, tabAlias, alias) && dep != null) {
          cond.getBaseCols().addAll(dep.getBaseCols());
        }
        return tabAlias + "." + alias;
      }

      if (dep != null) {
        if (cond != null) {
          cond.getBaseCols().addAll(dep.getBaseCols());
        }
        if (dep.getExpr() != null) {
          return dep.getExpr();
        }
      }
      if (alias.startsWith("_")) {
        ci = inpOp.getSchema().getColumnInfo(columnName);
        if (ci != null) {
          alias = ci.getAlias();
        }
      }
      return alias;
    } else if (expr instanceof ExprNodeGenericFuncDesc) {
      ExprNodeGenericFuncDesc func = (ExprNodeGenericFuncDesc) expr;
      List<ExprNodeDesc> children = func.getChildren();
      String[] childrenExprStrings = new String[children.size()];
      for (int i = 0; i < childrenExprStrings.length; i++) {
        childrenExprStrings[i] = getExprString(rs, children.get(i), lctx, inpOp, cond);
      }
      return func.getGenericUDF().getDisplayString(childrenExprStrings);
    }
    return expr.getExprString();
  }

  /**
   * Gets the expression dependencies for the expression.
   *
   * @param lctx
   *          The lineage context containing the input operators dependencies.
   * @param inpOp
   *          The input operator to the current operator.
   * @param expr
   *          The expression that is being processed.
   * @throws SemanticException
   */
  public static Dependency getExprDependency(LineageCtx lctx,
      Operator<? extends OperatorDesc> inpOp, ExprNodeDesc expr)
      throws SemanticException {

    // Create the walker, the rules dispatcher and the context.
    ExprProcCtx exprCtx = new ExprProcCtx(lctx, inpOp);

    // create a walker which walks the tree in a DFS manner while maintaining
    // the operator stack. The dispatcher
    // generates the plan from the operator tree
    Map<Rule, NodeProcessor> exprRules = new LinkedHashMap<Rule, NodeProcessor>();
    exprRules.put(
        new RuleRegExp("R1", ExprNodeColumnDesc.class.getName() + "%"),
        getColumnProcessor());
    exprRules.put(
        new RuleRegExp("R2", ExprNodeFieldDesc.class.getName() + "%"),
        getFieldProcessor());
    exprRules.put(new RuleRegExp("R3", ExprNodeGenericFuncDesc.class.getName()
        + "%"), getGenericFuncProcessor());

    // The dispatcher fires the processor corresponding to the closest matching
    // rule and passes the context along
    Dispatcher disp = new DefaultRuleDispatcher(getDefaultExprProcessor(),
        exprRules, exprCtx);
    GraphWalker egw = new DefaultGraphWalker(disp);

    List<Node> startNodes = new ArrayList<Node>();
    startNodes.add(expr);

    HashMap<Node, Object> outputMap = new HashMap<Node, Object>();
    egw.startWalking(startNodes, outputMap);
    return (Dependency)outputMap.get(expr);
  }
}
