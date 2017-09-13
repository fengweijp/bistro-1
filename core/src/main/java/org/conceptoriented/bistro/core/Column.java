package org.conceptoriented.bistro.core;

import org.conceptoriented.bistro.core.formula.*;

import java.util.*;
import java.util.stream.Collectors;

public class Column {
	private Schema schema;
	public Schema getSchema() {
		return this.schema;
	}
	
	private final UUID id;
	public UUID getId() {
		return this.id;
	}

	private String name;
	public String getName() {
		return this.name;
	}
	public void setName(String name) {
		this.name = name;
	}
	
	private Table input;
	public Table getInput() {
		return this.input;
	}
	public void setInput(Table table) {
		this.input = table;
	}

	private Table output;
	public Table getOutput() {
		return this.output;
	}
	public void setOutput(Table table) { this.output = table; this.setValue(null); }

	private boolean key = false;
	public boolean isKey() {  return this.key; }

    //
	// Data (public)
	//

    private ColumnData data;

    public Object getValue(long id) { return this.data.getValue(id); }

    public void setValue(long id, Object value) { this.data.setValue(id, value); this.isDirty = true; }

    public void setValue(Object value) { this.data.setValue(value); this.isDirty = true; }

    public void setValue() { this.data.setValue(); this.isDirty = true; }

    //
    // Data (protected). These are used from Table only (all columns change their ranges simultaniously) and from users - users add/remove elements via tables.
    //

    protected void add() { this.data.add(); this.isDirty = true; }
	protected void add(long count) { this.data.add(count); this.isDirty = true; }

    protected void remove() { this.data.remove(1); this.isDirty = true; }
    protected void remove(long count) { this.data.remove(count); this.isDirty = true; }

    //
    // Data dirty state (~hasDirtyDeep)
    //
    // 0) for USER columns (!isDerived) is defined and interpreted by the user -> USER columns do not participate in dependencies/evaluation, so since USER columns are ignored by evaluate procedure - isDirty is also ignored.

    // 1) add/remove ids in this input (input set population changes) -> what about dependencies?
    // 2) set this output values (this function changes) -> or any of its dependencies recursively
    // 3) definition change of this -> or any of its dependencies
    // 4) definition error of this -> or any of its dependencies

    private boolean isDirty = false;
    public boolean isDirty() {
        return this.isDirty;
    }
    public void setDirty() {
        this.isDirty = true;
    }

    protected boolean hasDirtyDeep() { // This plus inherited dirty status
        if(this.isDirty) return true;

        // Otherwise check if there is a dirty dependency (recursively)
        for(Column dep : this.getDependencies()) {
            if(dep.hasDirtyDeep()) return true;
        }

        return false;
    }

    //
    // Dependencies
    //

    protected List<Column> getDependencies() {
        if(this.evaluator == null) return new ArrayList<>();
        List<Column> deps = this.evaluator.getDependencies();
        if(deps == null) return new ArrayList<>();
        return deps;
    }
    // Get all unique dependencies of the specified columns
    protected List<Column> getDependencies(List<Column> cols) {
        List<Column> ret = new ArrayList<>();
        for(Column col : cols) {
            List<Column> deps = col.getDependencies();
            for(Column d : deps) {
                if(!ret.contains(d)) ret.add(d);
            }
        }
        return ret;
    }

    // Get all columns that (directly) depend on this column
    protected List<Column> getDependants() {
        List<Column> res = schema.getColumns().stream().filter(x -> x.getDependencies().contains(this)).collect(Collectors.<Column>toList());
        return res;
    }

    // Checks if this column depends on itself
    protected boolean isInCyle() {
        for(List<Column> deps = this.getDependencies(); deps.size() > 0; deps = this.getDependencies(deps)) {
            for(Column dep : deps) {
                if(dep == this) {
                    return true;
                }
            }
        }
        return false;
    }

    //
    // Execution errors (cleaned, and then produced after each new evaluation)
    //

    private List<BistroError> evaluationErrors = new ArrayList<>();
    public List<BistroError> getEvaluationErrors() { // Empty list in the case of no errors
        return this.evaluationErrors;
    }

    protected boolean hasEvaluationErrorsDeep() {
        if(evaluationErrors.size() > 1) return true; // Check this column

        // Otherwise check evaluationErrors in dependencies (recursively)
        for(List<Column> deps = this.getDependencies(); deps.size() > 0; deps = this.getDependencies(deps)) {
            for(Column dep : deps) {
                if(dep == this) return true;
                if(dep.getEvaluationErrors().size() > 0) return true;
            }
        }

        return false;
    }

    //
    // Evaluate
    //

    ColumnDefinition evaluator; // It is instantiated by cal-link-accu methods (or translate errors are added)

    // The strategy is to start from the goal (this column), recursively evaluate all dependencies and finally evaluate this column
    public void evaluate() {

        // Skip non-derived columns - they do not participate in evaluation
        if(!this.isDerived()) {
            if(this.isDirty()) {
                this.getDependants().forEach(x -> x.setDirty());
            }
            this.isDirty = false;
            return;
        }

        // Clear all evaluation errors before any new evaluation
        this.evaluationErrors.clear();

        // If there are some definition errors then no possibility to evaluate (including cycles)
        if(this.hasDefinitionErrorsDeep()) { // this.canEvalute false
            return;
        }
        // No definition errors - canEvaluate true

        // If everything is up-to-date then there is no need to evaluate
        if(!this.hasDirtyDeep()) { // this.needEvaluate false
            return;
        }
        // There exists dirty status - needEvaluate true

        // Evaluate dependencies recursively
        for(List<Column> deps = this.getDependencies(); deps.size() > 0; deps = this.getDependencies(deps)) {
            for(Column dep : deps) {
                dep.evaluate(); // Whether it is really evaluated depends on the need (dirty status etc.)
            }
        }

        // If there were some evaluation errors
        if(this.hasEvaluationErrorsDeep()) { // this.canEvaluate false
            return;
        }
        // No errors while evaluating dependencies

        // All dependencies are ok so this column can be evaluated
        this.evaluator.evaluate();

        this.evaluationErrors.addAll(this.evaluator.getErrors());

        if(this.evaluationErrors.size() == 0) {
            this.isDirty = false; // Clean the state (remove dirty flag)
        }
        else {
            this.isDirty = true; // Evaluation failed
        }
    }

    //
    // Formula definitionType
    //

    protected ColumnDefinitionType definitionType;
    public ColumnDefinitionType getDefinitionType() {
        return this.definitionType;
    }
    public void setDefinitionType(ColumnDefinitionType definitionType) {
        this.definitionType = definitionType;
        this.definitionErrors.clear();
        this.evaluationErrors.clear();
        this.evaluator = null;
        this.isDirty = true;
    }
    public boolean isDerived() {
        if(this.definitionType == ColumnDefinitionType.CALC || this.definitionType == ColumnDefinitionType.ACCU || this.definitionType == ColumnDefinitionType.LINK) {
            return true;
        }
        return false;
    }

    //
    // Definition errors. Produced after each new definition
    //

    private List<BistroError> definitionErrors = new ArrayList<>();
    public List<BistroError> getDefinitionErrors() { // Empty list in the case of no errors
        return this.definitionErrors;
    }

    public boolean hasDefinitionErrorsDeep() { // Recursively
        if(this.definitionErrors.size() > 0) return true; // Check this column

        // Otherwise check errors in dependencies (recursively)
        for(List<Column> deps = this.getDependencies(); deps.size() > 0; deps = this.getDependencies(deps)) {
            for(Column dep : deps) {
                if(dep == this) return true;
                if(dep.getDefinitionErrors().size() > 0) return true;
            }
        }

        return false;
    }

    // Patterns:
    // 1) calculate(Ude.class/Ude.name, formula) <- here we provide translator plus proceudre/binding (new instance will be given formula string)
    // 2) calculate(lambda/Ude.class/Ude.name, path_objects/names) <- here we provide procedure plus binding (instance will be given parameter paths)

    //
    // Calcuate. Convert input parameters into an Evaluator object and ealuate it in the case of immediate (eager) action.
    //

    public void calculate(Evaluator lambda, List<ColumnPath> paths) { // Specify lambda and parameter paths
        this.setDefinitionType(ColumnDefinitionType.CALC); // Reset definition

        this.evaluator = new ColumnDefinitionCalc(this, lambda, paths); // Create evaluator
        // TODO: Proces errors. Add excpeitons to the declaration of creator

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
            return;
        }
    }

    public void calculate(Class clazz, List<ColumnPath> paths) {
        this.setDefinitionType(ColumnDefinitionType.CALC); // Reset definition

        // Instantiate the specified class
        Object instance = null;
        try {
            instance = (Evaluator)clazz.newInstance();
        } catch (Exception e) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Instantiation error.", "Cannot create instance of the specified class.", e));
            return;
        }

        Expression expr = null;
        if(instance instanceof Expression) {
            this.evaluator = new ColumnDefinitionCalc(this, (Expression) instance, paths);
        }
        else if(instance instanceof Evaluator) {
            this.evaluator = new ColumnDefinitionCalc(this, (Evaluator) instance, paths);
        }
        else {
            // TODO: Error: wrong class
            return;
        }

        // TODO: Process errors (same as above)

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void calculate(String formulaClass, String formula) { // Specify Expression class/selector and formula parameter for this class
        this.setDefinitionType(ColumnDefinitionType.CALC); // Reset definition

        Expression expr = null;
        try {
            expr = FormulaBase.createInstance(formulaClass, formula, this.input);
        }
        catch(BistroError e) {
            this.definitionErrors.add(e);
            return;
        }

        this.evaluator = new ColumnDefinitionCalc(this, expr);

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Link
    //

    public void link(List<Column> columns, List<Expression> exprs) { // Custom rhs UDEs for each lhs column
        this.setDefinitionType(ColumnDefinitionType.LINK); // Reset definition

        this.evaluator = new ColumnDefinitionLink(this, columns, exprs);

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void link(String formulaClass, List<String> names, List<String> formulas) { // Column names in the output table and expressions in the input table
        this.setDefinitionType(ColumnDefinitionType.LINK); // Reset definition

        // Resolve all names
        List<Column> columns = new ArrayList<>();
        for(String name : names) {
            Column col = this.output.getColumn(name);
            if(col == null) {
                this.definitionErrors.add(new BistroError(BistroErrorCode.NAME_RESOLUTION_ERROR, "Cannot resolve column name.", "Cannot resolve column name: " + name));
                return;
            }
            columns.add(col);
        }

        // Translate all formulas
        List<Expression> exprs = new ArrayList<>();
        for(String formula : formulas) {
            FormulaBase expr = null;
            try {
                expr = FormulaBase.createInstance(formulaClass, formula, this.input);
            }
            catch(BistroError e) {
                this.definitionErrors.add(e);
                return;
            }
            exprs.add(expr);
        }

        this.evaluator = new ColumnDefinitionLink(this, columns, exprs);

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
    // Accumulate
    //

    public void accumulate(Expression initExpr, Expression accuExpr, Expression finExpr, ColumnPath accuPath) { // Provide instance of custom UDEs which have already paths
        this.setDefinitionType(ColumnDefinitionType.ACCU); // Reset definition

        this.evaluator = new ColumnDefinitionAccu(this, initExpr, accuExpr, finExpr, accuPath);

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    public void accumulate(String formulaClass, String initFormula, String accuFormula, String finFormula, String accuTableName, NamePath accuLinkPath) { // Specify Expression class/selector and formulas
        this.setDefinitionType(ColumnDefinitionType.ACCU); // Reset definition

        // Accu table and link (group) path
        Table accuTable = schema.getTable(accuTableName);
        if(accuTable == null) { // Binding error
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Binding error.", "Cannot find table: " + accuTableName));
            return;
        }

        // Accu (group) path
        ColumnPath accuPathColumns = accuLinkPath.resolveColumns(accuTable);
        if(accuPathColumns == null) { // Binding error
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Binding error.", "Cannot find columns: " + accuLinkPath.toString()));
            return;
        }

        // Initialization
        Expression initExpr = null;
        try {
            initExpr = FormulaBase.createInstance(formulaClass, initFormula, this.input);
        }
        catch(BistroError e) {
            this.definitionErrors.add(e);
            return;
        }

        // Accumulation
        Expression accuExpr = null;
        try {
            accuExpr = FormulaBase.createInstance(formulaClass, accuFormula, accuTable); // Note that we use a different table (not this column input)
        }
        catch(BistroError e) {
            this.definitionErrors.add(e);
            return;
        }

        // Finalization
        Expression finExpr = null;
        if(finFormula != null && !finFormula.isEmpty()) {
            try {
                finExpr = FormulaBase.createInstance(formulaClass, finFormula, this.input);
            }
            catch(BistroError e) {
                this.definitionErrors.add(e);
                return;
            }
        }

        this.evaluator = new ColumnDefinitionAccu(this, initExpr, accuExpr, finExpr, accuPathColumns);

        if(this.isInCyle()) {
            this.definitionErrors.add(new BistroError(BistroErrorCode.DEFINITION_ERROR, "Cyclic dependency.", "This column depends on itself directly or indirectly."));
        }
    }

    //
	// Serialization and construction
	//

	@Override
	public String toString() {
		return "[" + getName() + "]: " + input.getName() + " -> " + output.getName();
	}
	
	@Override
	public boolean equals(Object aThat) {
		if (this == aThat) return true;
		if ( !(aThat instanceof Table) ) return false;
		
		Column that = (Column)aThat;
		
		if(!that.getId().toString().equals(id.toString())) return false;
		
		return true;
	}

	public Column(Schema schema, String name, Table input, Table output) {
		this.schema = schema;
		this.id = UUID.randomUUID();
		this.name = name;
		this.input = input;
		this.output = output;
		
		// Formula
		this.definitionType = ColumnDefinitionType.NONE;

		// Data
		this.data = new ColumnData(this.input.getIdRange().start, this.input.getIdRange().end);
	}
}
