/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */
package net.sourceforge.pmd.lang.java.rule.coupling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.lang.java.ast.ASTAllocationExpression;
import net.sourceforge.pmd.lang.java.ast.ASTAssignmentOperator;
import net.sourceforge.pmd.lang.java.ast.ASTBlock;
import net.sourceforge.pmd.lang.java.ast.ASTMethodDeclaration;
import net.sourceforge.pmd.lang.java.ast.ASTName;
import net.sourceforge.pmd.lang.java.ast.ASTPrimaryExpression;
import net.sourceforge.pmd.lang.java.ast.ASTPrimaryPrefix;
import net.sourceforge.pmd.lang.java.ast.ASTPrimarySuffix;
import net.sourceforge.pmd.lang.java.ast.ASTVariableDeclarator;
import net.sourceforge.pmd.lang.java.ast.ASTVariableDeclaratorId;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.java.symboltable.LocalScope;
import net.sourceforge.pmd.lang.java.symboltable.Scope;
import net.sourceforge.pmd.lang.java.symboltable.VariableNameDeclaration;

/**
 * This rule can detect possible violations of the Law of Demeter.
 * The Law of Demeter is a simple rule, that says "only talk to friends". It helps to reduce
 * coupling between classes or objects.
 * <p>
 * See:
 * <ul>
 *   <li>Andrew Hunt, David Thomas, and Ward Cunningham. The Pragmatic Programmer. From Journeyman to Master. Addison-Wesley Longman, Amsterdam, October 1999.</li>
 *   <li>K.J. Lieberherr and I.M. Holland. Assuring good style for object-oriented programs. Software, IEEE, 6(5):38–48, 1989.</li>
 * </ul>
 * 
 * @since 5.0
 *
 */
public class LawOfDemeterRule extends AbstractJavaRule {
    private static final String REASON_METHOD_CHAIN_CALLS = "method chain calls";
    private static final String REASON_OBJECT_NOT_CREATED_LOCALLY = "object not created locally";
    private static final String REASON_STATIC_ACCESS = "static property access";
    
    /**
     * That's a new method. We are going to check each method call inside the method.
     * @return <code>null</code>.
     */
    @Override
    public Object visit(ASTMethodDeclaration node, Object data) {
        List<ASTPrimaryExpression> primaryExpressions = node.findDescendantsOfType(ASTPrimaryExpression.class);
        for (ASTPrimaryExpression expression : primaryExpressions) {
            List<MethodCall> calls = MethodCall.createMethodCalls(expression);
            addViolations(calls, (RuleContext)data);
        }
        return null;
    }
    
    private void addViolations(List<MethodCall> calls, RuleContext ctx) {
        for (MethodCall method : calls) {
            if (method.isViolation()) {
                addViolationWithMessage(ctx, method.getExpression(), getMessage() + " (" + method.getViolationReason() + ")");
            }
        }
    }
    
    
    /**
     * Collects the information of one identified method call. The method call
     * might be a violation of the Law of Demeter or not.
     */
    private static class MethodCall {
        private static final String METHOD_CALL_CHAIN = "result from previous method call";
        private static final String SIMPLE_ASSIGNMENT_OPERATOR = "=";
        private static final String SCOPE_METHOD_CHAINING = "method-chaining";
        private static final String SCOPE_CLASS = "class";
        private static final String SCOPE_METHOD = "method";
        private static final String SCOPE_LOCAL = "local";
        private static final String SCOPE_STATIC_CHAIN = "static-chain";
        private static final String SUPER = "super";
        private static final String THIS = "this";
        
        private ASTPrimaryExpression expression;
        private String baseName;
        private String methodName;
        private String baseScope;
        private String baseTypeName;
        private Class<?> baseType;
        private boolean violation;
        private String violationReason;
        
        /**
         * Create a new method call for the prefix expression part of the primary expression.
         */
        private MethodCall(ASTPrimaryExpression expression, ASTPrimaryPrefix prefix) {
            this.expression = expression;
            analyze(prefix);
            determineType();
            checkViolation();
        }

        /**
         * Create a new method call for the given suffix expression part of the primary expression.
         * This is used for method chains.
         */
        private MethodCall(ASTPrimaryExpression expression, ASTPrimarySuffix suffix) {
            this.expression = expression;
            analyze(suffix);
            determineType();
            checkViolation();
        }
        
        /**
         * Factory method to convert a given primary expression into MethodCalls.
         * In case the primary expression represents a method chain call, then multiple
         * MethodCalls are returned.
         * 
         * @return a list of MethodCalls, might be empty.
         */
        public static List<MethodCall> createMethodCalls(ASTPrimaryExpression expression) {
            List<MethodCall> result = new ArrayList<MethodCall>();

            if (isNotAConstructorCall(expression) && hasSuffixesWithArguments(expression)) {
                ASTPrimaryPrefix prefixNode = expression.getFirstDescendantOfType(ASTPrimaryPrefix.class);
                result.add(new MethodCall(expression, prefixNode));
                
                List<ASTPrimarySuffix> suffixes = findSuffixesWithoutArguments(expression);
                for (ASTPrimarySuffix suffix : suffixes) {
                    result.add(new MethodCall(expression, suffix));
                }
            }
            
            return result;
        }
        
        private static boolean isNotAConstructorCall(ASTPrimaryExpression expression) {
            return !expression.hasDescendantOfType(ASTAllocationExpression.class);
        }

        private static List<ASTPrimarySuffix> findSuffixesWithoutArguments(ASTPrimaryExpression expr) {
            List<ASTPrimarySuffix> result = new ArrayList<ASTPrimarySuffix>();
            if (hasRealPrefix(expr)) {
                List<ASTPrimarySuffix> suffixes = expr.findDescendantsOfType(ASTPrimarySuffix.class);
                for (ASTPrimarySuffix suffix : suffixes) {
                    if (!suffix.isArguments()) {
                        result.add(suffix);
                    }
                }
            }
            return result;
        }
        
        private static boolean hasRealPrefix(ASTPrimaryExpression expr) {
            ASTPrimaryPrefix prefix = expr.getFirstDescendantOfType(ASTPrimaryPrefix.class);
            return !prefix.usesThisModifier() && !prefix.usesSuperModifier();
        }
        
        private static boolean hasSuffixesWithArguments(ASTPrimaryExpression expr) {
            boolean result = false;
            if (hasRealPrefix(expr)) {
                List<ASTPrimarySuffix> suffixes = expr.findDescendantsOfType(ASTPrimarySuffix.class);
                for (ASTPrimarySuffix suffix : suffixes) {
                    if (suffix.isArguments()) {
                        result = true;
                        break;
                    }
                }
            }
            return result;
        }

        private void analyze(ASTPrimaryPrefix prefixNode) {
            List<ASTName> names = prefixNode.findDescendantsOfType(ASTName.class);
            
            baseName = "unknown";
            methodName = "unknown";
            
            if (!names.isEmpty()) {
                baseName = names.get(0).getImage();
                
                int dot = baseName.lastIndexOf('.');
                if (dot == -1) {
                    methodName = baseName;
                    baseName = THIS;
                } else {
                    methodName = baseName.substring(dot + 1);
                    baseName = baseName.substring(0, dot);
                }
                
            } else {
                if (prefixNode.usesThisModifier()) {
                    baseName = THIS;
                } else if (prefixNode.usesSuperModifier()) {
                    baseName = SUPER;
                }
            }
        }
        
        private void analyze(ASTPrimarySuffix suffix) {
            baseName = METHOD_CALL_CHAIN;
            methodName = suffix.getImage();
        }
        
        private void checkViolation() {
            violation = false;
            violationReason = null;
            
            if (SCOPE_LOCAL.equals(baseScope)) {
                Assignment lastAssignment = determineLastAssignment();
                if (lastAssignment != null && !lastAssignment.allocation && !lastAssignment.iterator) {
                    violation = true;
                    violationReason = REASON_OBJECT_NOT_CREATED_LOCALLY;
                }
            } else if (SCOPE_METHOD_CHAINING.equals(baseScope)) {
                violation = true;
                violationReason = REASON_METHOD_CHAIN_CALLS;
            } else if (SCOPE_STATIC_CHAIN.equals(baseScope)) {
                violation = true;
                violationReason = REASON_STATIC_ACCESS;
            }
        }
        
        private void determineType() {
            VariableNameDeclaration var = null;
            Scope scope = expression.getScope();
            
            baseScope = SCOPE_LOCAL;
            var = findInLocalScope(baseName, (LocalScope)scope);
            if (var == null) {
                baseScope = SCOPE_METHOD;
                var = determineTypeOfVariable(baseName, scope.getEnclosingMethodScope().getVariableDeclarations().keySet());
            }
            if (var == null) {
                baseScope = SCOPE_CLASS;
                var = determineTypeOfVariable(baseName, scope.getEnclosingClassScope().getVariableDeclarations().keySet());
            }
            if (var == null) {
                baseScope = SCOPE_METHOD_CHAINING;
            }
            if (var == null && (THIS.equals(baseName) || SUPER.equals(baseName))) {
                baseScope = SCOPE_CLASS;
            }
            
            if (var != null) {
                baseTypeName = var.getTypeImage();
                baseType = var.getType();
            } else if (METHOD_CALL_CHAIN.equals(baseName)) {
                baseScope = SCOPE_METHOD_CHAINING;
            } else if (baseName.contains(".") && !baseName.startsWith("System.")) {
                baseScope = SCOPE_STATIC_CHAIN;
            } else {
                // everything else is no violation - probably a static method call.
                baseScope = null;
            }
        }
        
        private VariableNameDeclaration findInLocalScope(String name, LocalScope scope) {
            VariableNameDeclaration result = null;
            
            result = determineTypeOfVariable(name, scope.getVariableDeclarations().keySet());
            if (result == null && scope.getParent() instanceof LocalScope) {
                result = findInLocalScope(name, (LocalScope)scope.getParent());
            }
            
            return result;
        }

        private VariableNameDeclaration determineTypeOfVariable(String variableName, Set<VariableNameDeclaration> declarations) {
            VariableNameDeclaration result = null;
            for (VariableNameDeclaration var : declarations) {
                if (variableName.equals(var.getImage())) {
                    result = var;
                    break;
                }
            }
            return result;
        }
        
        private Assignment determineLastAssignment() {
            List<Assignment> assignments = new ArrayList<Assignment>();
            
            ASTBlock block = expression.getFirstParentOfType(ASTMethodDeclaration.class).getFirstChildOfType(ASTBlock.class);
            
            List<ASTVariableDeclarator> variableDeclarators = block.findDescendantsOfType(ASTVariableDeclarator.class);
            for (ASTVariableDeclarator declarator : variableDeclarators) {
                ASTVariableDeclaratorId variableDeclaratorId = declarator.getFirstChildOfType(ASTVariableDeclaratorId.class);
                if (variableDeclaratorId.hasImageEqualTo(baseName)) {
                    boolean allocationFound = declarator.getFirstDescendantOfType(ASTAllocationExpression.class) != null;
                    boolean iterator = isIterator();
                    assignments.add(new Assignment(declarator.getBeginLine(), allocationFound, iterator));
                }
            }
            
            List<ASTAssignmentOperator> assignmentStmts = block.findDescendantsOfType(ASTAssignmentOperator.class);
            for (ASTAssignmentOperator stmt : assignmentStmts) {
                if (stmt.hasImageEqualTo(SIMPLE_ASSIGNMENT_OPERATOR)) {
                    boolean allocationFound = stmt.jjtGetParent().getFirstDescendantOfType(ASTAllocationExpression.class) != null;
                    boolean iterator = isIterator();
                    assignments.add(new Assignment(stmt.getBeginLine(), allocationFound, iterator));
                }
            }
            
            Assignment result = null;
            if (!assignments.isEmpty()) {
                Collections.sort(assignments);
                result = assignments.get(0);
            }
            return result;
        }
        
        private boolean isIterator() {
            boolean iterator = false;
            if ((baseType != null && baseType == Iterator.class)
                    || (baseTypeName != null && baseTypeName.endsWith("Iterator"))) {
                iterator = true;
            }
            return iterator;
        }
        
        public ASTPrimaryExpression getExpression() {
            return expression;
        }
        
        public boolean isViolation() {
            return violation;
        }
        
        public String getViolationReason() {
            return violationReason;
        }
        
        @Override
        public String toString() {
            return "MethodCall on line " + expression.getBeginLine() + ":\n"
                + "  " + baseName + " name: "+ methodName+ "\n"
                + "  type: " + baseTypeName + " (" + baseType + "), \n"
                + "  scope: " + baseScope + "\n"
                + "  violation: " + violation + " (" + violationReason + ")\n";
        }
        
    }
    
    /**
     * Stores the assignment of a variable and whether the variable's value is
     * allocated locally (new constructor call). The class is comparable, so that
     * the last assignment can be determined.
     */
    private static class Assignment implements Comparable<Assignment> {
        private int line;
        private boolean allocation;
        private boolean iterator;
        
        public Assignment(int line, boolean allocation, boolean iterator) {
            this.line = line;
            this.allocation = allocation;
            this.iterator = iterator;
        }
        
        @Override
        public String toString() {
            return "assignment: line=" + line + " allocation:" + allocation
                + " iterator:" + iterator;
        }

        public int compareTo(Assignment o) {
            return o.line - line;
        }
    }
}
