/*
 * Copyright (C) 2009-2017 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.core.AST.Kind.TYPE;
import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.JavacTreeMaker.TreeTag.treeTag;
import static lombok.javac.handlers.HandleConstructor.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.isFieldDeprecated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.ConfigurationKeys;
import lombok.NoArgsConstructor;
import lombok.PartialFieldsResolver;
import lombok.RequiredArgsConstructor;
import lombok.core.AST;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.core.handlers.HandlerUtil.FieldAccess;
import lombok.delombok.LombokOptionsFactory;
import lombok.experimental.Accessors;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TreeTag;
import lombok.javac.handlers.HandleConstructor.SkipIfConstructorExists;
import lombok.javac.handlers.JavacHandlerUtil.CopyJavadoc;
import lombok.javac.handlers.JavacHandlerUtil.MemberExistsResult;

/**
 * Handles the {@code lombok.Resolver} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandlePartialFieldsResolver extends JavacAnnotationHandler<PartialFieldsResolver> {
	
	private static final String FIELDS_LIST_FIELD_NAME = "$$_partial_fields_list";
	private static final String FIELDS_LIST_FIELD_CONSTRUCTOR_PARAM_NAME = "fields";
	private static final String FIELDS_LIST_TYPE = "java.util.List";
	private static final String JSONIGNORE_LIST_TYPE = "com.fasterxml.jackson.annotation.JsonIgnore";
	private static final String JAVA_UTIL_ARRAYS_TYPE_NAME = "java.util.Arrays";
	
	private static final String STRING_TYPE = "java.lang.String";
	private static final String JAVA_SUPPLIER = "java.util.function.Supplier";
	private static final String RESOLVER_PREFIX = "resolve";
	
	private static final TreeTag CTC_OR = treeTag("OR");
	private static final TreeTag CTC_AND = treeTag("AND");

	/** Matches the simple part of any annotation that lombok considers as indicative of NonNull status. */
	public static final Pattern NON_NULL_PATTERN = Pattern.compile("^(?:nonnull)$", Pattern.CASE_INSENSITIVE);
	
	/** Matches the simple part of any annotation that lombok considers as indicative of Nullable status. */
	public static final Pattern NULLABLE_PATTERN = Pattern.compile("^(?:nullable|checkfornull)$", Pattern.CASE_INSENSITIVE);
	
	public void generateResolverForType(JavacNode typeNode, JavacNode annotationNode, PartialFieldsResolver annotationInstance, AccessLevel level, boolean checkForTypeLevelResolver, List<JCAnnotation> onMethod, List<JCAnnotation> onParam) {
		if (checkForTypeLevelResolver) {
			if (hasAnnotation(PartialFieldsResolver.class, typeNode)) {
				// The annotation will make it happen, so we can skip it.
				return;
			}
		}
		
		JCClassDecl typeDecl = null;
		if (typeNode.get() instanceof JCClassDecl) typeDecl = (JCClassDecl) typeNode.get();
		long modifiers = typeDecl == null ? 0 : typeDecl.mods.flags;
		boolean notAClass = (modifiers & (Flags.INTERFACE | Flags.ANNOTATION | Flags.ENUM)) != 0;
		
		if (typeDecl == null || notAClass) {
			annotationNode.addError("@PartialFieldsResolver is only supported on a class.");
			return;
		}
		
		// Generate list of fields
		JavacNode fieldsListNode = createFieldsListField(typeNode, annotationNode, annotationInstance);
		
		// Generate Constructors
		createConstructors(typeNode, fieldsListNode, annotationNode, annotationInstance);
		
		for (JavacNode field : typeNode.down()) {
			if (field.getKind() != Kind.FIELD) continue;
			JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
			// Skip fields that start with $
			if (fieldDecl.name.toString().startsWith("$")) continue;
			// Skip static fields.
			if ((fieldDecl.mods.flags & Flags.STATIC) != 0) continue;
			// Skip final fields.
			if ((fieldDecl.mods.flags & Flags.FINAL) != 0) continue;
			// Skip Fields List field
			if ((fieldDecl.name.toString().equals(FIELDS_LIST_FIELD_NAME))) continue;
			
			generateResolverForField(field, annotationNode, level, onMethod, onParam, fieldsListNode, annotationInstance);
		}
	}
	
	private void createConstructors(JavacNode typeNode, JavacNode field, JavacNode source, PartialFieldsResolver annotationInstance) {
		
		List<JCAnnotation> onConstructor = List.<JCAnnotation>nil();
		SkipIfConstructorExists skipIfConstructorExists = SkipIfConstructorExists.NO;
		
		if (skipIfConstructorExists != SkipIfConstructorExists.NO) {
			for (JavacNode child : typeNode.down()) {
				if (child.getKind() == Kind.ANNOTATION) {
					boolean skipGeneration = annotationTypeMatches(NoArgsConstructor.class, child) ||
						annotationTypeMatches(AllArgsConstructor.class, child) ||
						annotationTypeMatches(RequiredArgsConstructor.class, child);
					
					if (!skipGeneration && skipIfConstructorExists == SkipIfConstructorExists.YES) {
						skipGeneration = annotationTypeMatches(Builder.class, child);
					}
					if (skipGeneration) {
						return;
					}
				}
			}
		}
		
		ListBuffer<Type> argTypes = new ListBuffer<Type>();
		for (JavacNode fieldNode : List.of(field)) {
			Type mirror = getMirrorForFieldType(fieldNode);
			if (mirror == null) {
				argTypes = null;
				break;
			}
			argTypes.append(mirror);
		}
		List<Type> argTypes_ = argTypes == null ? null : argTypes.toList();

		if (!(skipIfConstructorExists != SkipIfConstructorExists.NO && constructorExists(typeNode) != MemberExistsResult.NOT_EXISTS)) {
			JCMethodDecl constr = createConstructor(AccessLevel.PUBLIC, onConstructor, typeNode, List.of(field), true, source);
			injectMethod(typeNode, constr, argTypes_, Javac.createVoidType(typeNode.getSymbolTable(), CTC_VOID));
			
			if (annotationInstance.buildAdicionalFieldsListStringArrayConstructor()) {
				JCMethodDecl constrNasa = createConstructorFieldsListStringArray(AccessLevel.PUBLIC, onConstructor, typeNode, List.of(field), true, source);
				injectMethod(typeNode, constrNasa, argTypes_, Javac.createVoidType(typeNode.getSymbolTable(), CTC_VOID));
			}
		}
	}
	
	private static JCMethodDecl createConstructor(AccessLevel level, List<JCAnnotation> onConstructor, JavacNode typeNode, List<JavacNode> fieldsToParam, boolean forceDefaults, JavacNode source) {
		JavacTreeMaker maker = typeNode.getTreeMaker();
		
		boolean isEnum = (((JCClassDecl) typeNode.get()).mods.flags & Flags.ENUM) != 0;
		if (isEnum) level = AccessLevel.PRIVATE;
		
		boolean addConstructorProperties;
		
		if (fieldsToParam.isEmpty()) {
			addConstructorProperties = false;
		} else {
			Boolean v = typeNode.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_ADD_CONSTRUCTOR_PROPERTIES);
			addConstructorProperties = v != null ? v.booleanValue() : Boolean.FALSE.equals(typeNode.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_SUPPRESS_CONSTRUCTOR_PROPERTIES));
		}
		
		ListBuffer<JCStatement> nullChecks = new ListBuffer<JCStatement>();
		ListBuffer<JCStatement> assigns = new ListBuffer<JCStatement>();
		ListBuffer<JCVariableDecl> params = new ListBuffer<JCVariableDecl>();
		
		for (JavacNode fieldNode : fieldsToParam) {
			JCVariableDecl field = (JCVariableDecl) fieldNode.get();
			Name fieldName = fieldNode.toName(FIELDS_LIST_FIELD_CONSTRUCTOR_PARAM_NAME);// removePrefixFromField(fieldNode);
			Name rawName = field.name;
			List<JCAnnotation> copyableAnnotations = findCopyableAnnotations(fieldNode);
			long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, typeNode.getContext());
			JCExpression pType = cloneType(fieldNode.getTreeMaker(), field.vartype, source.get(), source.getContext());
			JCVariableDecl param = maker.VarDef(maker.Modifiers(flags, copyableAnnotations), fieldName, pType, null);
			params.append(param);
			if (hasNonNullAnnotations(fieldNode)) {
				JCStatement nullCheck = generateNullCheck(maker, param, source);
				if (nullCheck != null) nullChecks.append(nullCheck);
			}
			JCFieldAccess thisX = maker.Select(maker.Ident(fieldNode.toName("this")), rawName);
			JCExpression assign = maker.Assign(thisX, maker.Ident(fieldName));
			assigns.append(maker.Exec(assign));
		}
		
		JCModifiers mods = maker.Modifiers(toJavacModifier(level), List.<JCAnnotation>nil());
		if (addConstructorProperties && !isLocalType(typeNode) && LombokOptionsFactory.getDelombokOptions(typeNode.getContext()).getFormatPreferences().generateConstructorProperties()) {
			addConstructorProperties(mods, typeNode, fieldsToParam);
		}
		if (onConstructor != null) mods.annotations = mods.annotations.appendList(copyAnnotations(onConstructor));
		if (getCheckerFrameworkVersion(source).generateUnique()) mods.annotations = mods.annotations.prepend(maker.Annotation(genTypeRef(source, CheckerFrameworkVersion.NAME__UNIQUE), List.<JCExpression>nil()));
		return recursiveSetGeneratedBy(maker.MethodDef(mods, typeNode.toName("<init>"), null, List.<JCTypeParameter>nil(), params.toList(), List.<JCExpression>nil(), maker.Block(0L, nullChecks.appendList(assigns).toList()), null), source.get(), typeNode.getContext());
	}
	
	private static JCMethodDecl createConstructorFieldsListStringArray(AccessLevel level, List<JCAnnotation> onConstructor, JavacNode typeNode, List<JavacNode> fieldsToParam, boolean forceDefaults, JavacNode source) {
		JavacTreeMaker maker = typeNode.getTreeMaker();
		
		boolean isEnum = (((JCClassDecl) typeNode.get()).mods.flags & Flags.ENUM) != 0;
		if (isEnum) level = AccessLevel.PRIVATE;
		
		boolean addConstructorProperties;
		
		if (fieldsToParam.isEmpty()) {
			addConstructorProperties = false;
		} else {
			Boolean v = typeNode.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_ADD_CONSTRUCTOR_PROPERTIES);
			addConstructorProperties = v != null ? v.booleanValue() : Boolean.FALSE.equals(typeNode.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_SUPPRESS_CONSTRUCTOR_PROPERTIES));
		}
		
		ListBuffer<JCStatement> nullChecks = new ListBuffer<JCStatement>();
		ListBuffer<JCStatement> assigns = new ListBuffer<JCStatement>();
		ListBuffer<JCVariableDecl> params = new ListBuffer<JCVariableDecl>();
		
		for (JavacNode fieldNode : fieldsToParam) {
			JavacTreeMaker treeMaker = fieldNode.getTreeMaker();
			JCVariableDecl field = (JCVariableDecl) fieldNode.get();
			Name fieldName = fieldNode.toName(FIELDS_LIST_FIELD_CONSTRUCTOR_PARAM_NAME);// removePrefixFromField(fieldNode);
			Name rawName = field.name;
			List<JCAnnotation> copyableAnnotations = findCopyableAnnotations(fieldNode);
			long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, typeNode.getContext());
			JCExpression pType = treeMaker.TypeArray(chainDotsString(typeNode, STRING_TYPE));
			JCVariableDecl param = maker.VarDef(maker.Modifiers(flags, copyableAnnotations), fieldName, pType, null);
			params.append(param);
			if (hasNonNullAnnotations(fieldNode)) {
				JCStatement nullCheck = generateNullCheck(maker, param, source);
				if (nullCheck != null) nullChecks.append(nullCheck);
			}
			JCFieldAccess thisX = maker.Select(maker.Ident(fieldNode.toName("this")), rawName);
			JCExpression fn = treeMaker.Select(chainDotsString(typeNode, JAVA_UTIL_ARRAYS_TYPE_NAME), source.toName("asList"));
			JCExpression fieldParam = maker.Ident(fieldName);
			JCExpression asListArrayExpr = treeMaker.Apply(NIL_EXPRESSION, fn, List.of(fieldParam));
			JCExpression assign = maker.Assign(thisX, asListArrayExpr);
			assigns.append(maker.Exec(assign));
		}
		
		JCModifiers mods = maker.Modifiers(toJavacModifier(level), List.<JCAnnotation>nil());
		if (addConstructorProperties && !isLocalType(typeNode) && LombokOptionsFactory.getDelombokOptions(typeNode.getContext()).getFormatPreferences().generateConstructorProperties()) {
			addConstructorProperties(mods, typeNode, fieldsToParam);
		}
		if (onConstructor != null) mods.annotations = mods.annotations.appendList(copyAnnotations(onConstructor));
		if (getCheckerFrameworkVersion(source).generateUnique()) mods.annotations = mods.annotations.prepend(maker.Annotation(genTypeRef(source, CheckerFrameworkVersion.NAME__UNIQUE), List.<JCExpression>nil()));
		return recursiveSetGeneratedBy(maker.MethodDef(mods, typeNode.toName("<init>"), null, List.<JCTypeParameter>nil(), params.toList(), List.<JCExpression>nil(), maker.Block(0L, nullChecks.appendList(assigns).toList()), null), source.get(), typeNode.getContext());
	}
	
	private JavacNode createFieldsListField(JavacNode typeNode, JavacNode annotationNode, PartialFieldsResolver annotationInstance) {
		JavacTreeMaker maker = typeNode.getTreeMaker();
		JCExpression fieldType = maker.TypeApply(chainDotsString(typeNode, FIELDS_LIST_TYPE), List.<JCExpression>of(chainDotsString(typeNode, STRING_TYPE)));
		JCTree source = annotationNode.get();
		
		List<JCAnnotation> annotations = List.<JCAnnotation>nil();
		if (annotationInstance.addJsonIgnoreOnFieldsList()) {
			JCAnnotation jsonIgnoreAnnotation = maker.Annotation(chainDotsString(typeNode, JSONIGNORE_LIST_TYPE), List.<JCExpression>nil());
			annotations = List.of(jsonIgnoreAnnotation);
		}
		
		JCVariableDecl fieldsListDef = maker.VarDef(maker.Modifiers(Flags.PRIVATE, annotations), typeNode.toName(FIELDS_LIST_FIELD_NAME), fieldType, null);
		JCVariableDecl fieldsListDecl = recursiveSetGeneratedBy(fieldsListDef, source, typeNode.getContext());
		return injectField(typeNode, fieldsListDecl);
	}
	
	/**
	 * Generates a setter on the stated field.
	 * 
	 * Used by {@link HandleData}.
	 * 
	 * The difference between this call and the handle method is as follows:
	 * 
	 * If there is a {@code lombok.Resolver} annotation on the field, it is used
	 * and the same rules apply (e.g. warning if the method already exists,
	 * stated access level applies). If not, the setter is still generated if it
	 * isn't already there, though there will not be a warning if its already
	 * there. The default access level is used.
	 * 
	 * @param fieldNode
	 *            The node representing the field you want a setter for.
	 * @param fieldsListNode
	 * @param pos
	 *            The node responsible for generating the setter (the
	 *            {@code @Data} or {@code @PartialFieldsResolver} annotation).
	 */
	public void generateResolverForField(JavacNode fieldNode, JavacNode sourceNode, AccessLevel level, List<JCAnnotation> onMethod, List<JCAnnotation> onParam, JavacNode fieldsListNode, PartialFieldsResolver annotationInstance) {
		if (hasAnnotation(PartialFieldsResolver.class, fieldNode)) {
			// The annotation will make it happen, so we can skip it.
			return;
		}
		
		createResolverForField(level, fieldNode, sourceNode, false, onMethod, onParam, fieldsListNode, annotationInstance);
	}
	
	@Override public void handle(AnnotationValues<PartialFieldsResolver> annotation, JCAnnotation ast, JavacNode annotationNode) {
		
		handleFlagUsage(annotationNode, ConfigurationKeys.SETTER_FLAG_USAGE, "@PartialFieldsResolver");
		
		deleteAnnotationIfNeccessary(annotationNode, PartialFieldsResolver.class);
		deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
		JavacNode node = annotationNode.up();
		PartialFieldsResolver annotationInstance = annotation.getInstance();
		AccessLevel level = annotationInstance.level();
		
		if (level == AccessLevel.NONE || node == null) return;
		
		List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@PartialFieldsResolver(onMethod", annotationNode);
		List<JCAnnotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@PartialFieldsResolver(onParam", annotationNode);
		
		if (node.getKind() == TYPE) {
			generateResolverForType(node, annotationNode, annotationInstance, level, false, onMethod, onParam);
		}
	}
	
	public void createResolverForField(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, List<JCAnnotation> onMethod, List<JCAnnotation> onParam, JavacNode fieldsListNode, PartialFieldsResolver annotationInstance) {
		if (fieldNode.getKind() != Kind.FIELD) {
			fieldNode.addError("@PartialFieldsResolver is only supported on a class.");
			return;
		}
		
		JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();
		String methodName = toResolverName(fieldNode);
		
		if (methodName == null) {
			fieldNode.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		if ((fieldDecl.mods.flags & Flags.FINAL) != 0) {
			fieldNode.addWarning("Not generating setter for this field: Resolvers cannot be generated for final fields.");
			return;
		}
		
		for (String altName : toAllResolverNames(fieldNode)) {
			switch (methodExists(altName, fieldNode, false, 1)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					String altNameExpl = "";
					if (!altName.equals(methodName)) altNameExpl = String.format(" (%s)", altName);
					fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists%s", methodName, altNameExpl));
				}
				return;
			default:
			case NOT_EXISTS:
				// continue scanning the other alt names.
			}
		}
		
		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);
		
		JCMethodDecl createdResolver = createResolver(access, fieldNode, fieldNode.getTreeMaker(), sourceNode, onMethod, onParam, fieldsListNode, annotationInstance);
		Type fieldType = getMirrorForFieldType(fieldNode);
		Type returnType;
		
		if (shouldReturnThis(fieldNode)) {
			ClassSymbol sym = ((JCClassDecl) fieldNode.up().get()).sym;
			returnType = sym == null ? null : sym.type;
		} else {
			returnType = Javac.createVoidType(fieldNode.getSymbolTable(), CTC_VOID);
		}
		
		injectMethod(fieldNode.up(), createdResolver, fieldType == null ? null : List.of(fieldType), returnType);
	}
	
	public static JCMethodDecl createResolver(long access, JavacNode field, JavacTreeMaker treeMaker, JavacNode source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam, JavacNode fieldsListNode, PartialFieldsResolver annotationInstance) {
		String setterName = toResolverName(field);
		boolean returnThis = shouldReturnThis(field);
		return createResolver(access, false, field, treeMaker, setterName, null, returnThis, source, onMethod, onParam, fieldsListNode, annotationInstance);
	}
	
	public static JCMethodDecl createResolver(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker, String setterName, Name booleanFieldToSet, boolean shouldReturnThis, JavacNode source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam, JavacNode fieldsListNode, PartialFieldsResolver annotationInstance) {
		JCExpression returnType = null;
		JCReturn returnStatement = null;
		if (shouldReturnThis) {
			returnType = cloneSelfType(field);
			returnStatement = treeMaker.Return(treeMaker.Ident(field.toName("this")));
		}
		
		return createResolver(access, deprecate, field, treeMaker, setterName, booleanFieldToSet, returnType, returnStatement, source, onMethod, onParam, fieldsListNode, annotationInstance);
	}
	
	private static final List<JCExpression> NIL_EXPRESSION = List.nil();
	
	public static JCMethodDecl createResolver(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker, String resolverName, Name booleanFieldToSet, JCExpression methodType, JCStatement returnStatement, JavacNode source, List<JCAnnotation> onMethod, List<JCAnnotation> onParam, JavacNode fieldsListNode, PartialFieldsResolver annotationInstance) {
		if (resolverName == null) return null;
		
		JCExpression fieldsListAccessor = createFieldAccessor(treeMaker, fieldsListNode, FieldAccess.ALWAYS_FIELD);
		JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
		List<JCAnnotation> nonNulls = findAnnotations(field, NON_NULL_PATTERN);
		List<JCAnnotation> nullables = findAnnotations(field, NULLABLE_PATTERN);
		
		// Left assign
		JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
		
		// Right assign
		JCMethodInvocation invoke = treeMaker.Apply(NIL_EXPRESSION, treeMaker.Select(treeMaker.Ident(fieldDecl.name), source.toName("get")), NIL_EXPRESSION);
		
		// Assign
		JCAssign assign = treeMaker.Assign(fieldRef, invoke);
		
		// If Block Statements
		ListBuffer<JCStatement> ifBlockStatements = new ListBuffer<JCStatement>();
		if (nonNulls.isEmpty()) {
			ifBlockStatements.append(treeMaker.Exec(assign));
		} else {
			JCStatement nullCheck = generateNullCheck(treeMaker, field, source);
			if (nullCheck != null) ifBlockStatements.append(nullCheck);
			ifBlockStatements.append(treeMaker.Exec(assign));
		}
		
		// If
		JCBinary ifCondition;
		JCMethodInvocation isContainsField = treeMaker.Apply(NIL_EXPRESSION, treeMaker.Select(fieldsListAccessor, source.toName("contains")), List.of((JCExpression) treeMaker.Literal(field.getName())));
		
		if (annotationInstance.resolveMethodsIfFieldsListIsNullOrEmpty()) {
			JCBinary isNull = treeMaker.Binary(CTC_EQUAL, fieldsListAccessor, treeMaker.Literal(CTC_BOT, null));
			JCMethodInvocation isEmpty = treeMaker.Apply(NIL_EXPRESSION, treeMaker.Select(fieldsListAccessor, source.toName("isEmpty")), NIL_EXPRESSION);
			ifCondition = treeMaker.Binary(CTC_OR, treeMaker.Binary(CTC_OR, isNull, isEmpty), isContainsField);
		} else {
			JCBinary isNotNull = treeMaker.Binary(CTC_NOT_EQUAL, fieldsListAccessor, treeMaker.Literal(CTC_BOT, null));
			ifCondition = treeMaker.Binary(CTC_AND, isNotNull, isContainsField);
		}
		
		JCIf ifStatement = treeMaker.If(ifCondition, treeMaker.Block(0L, ifBlockStatements.toList()), null);
		
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		statements.append(ifStatement);
		
		Name methodName = field.toName(resolverName);
		List<JCAnnotation> annsOnParam = copyAnnotations(onParam).appendList(nonNulls).appendList(nullables);
		
		long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, field.getContext());
		
		// Method Param
		JCTypeApply paramType = treeMaker.TypeApply(lombok.javac.handlers.JavacHandlerUtil.chainDotsString(field, JAVA_SUPPLIER), List.<JCExpression>of(fieldDecl.vartype));
		JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(flags, annsOnParam), fieldDecl.name, paramType, null);
		
		

		if (booleanFieldToSet != null) {
			JCAssign setBool = treeMaker.Assign(treeMaker.Ident(booleanFieldToSet), treeMaker.Literal(CTC_BOOLEAN, 1));
			statements.append(treeMaker.Exec(setBool));
		}
		
		if (methodType == null) {
			// WARNING: Do not use field.getSymbolTable().voidType - that field
			// has gone through non-backwards compatible API changes within
			// javac1.6.
			methodType = treeMaker.Type(Javac.createVoidType(field.getSymbolTable(), CTC_VOID));
			returnStatement = null;
		}
		
		if (returnStatement != null) statements.append(returnStatement);
		
		JCBlock methodBody = treeMaker.Block(0, statements.toList());
		List<JCTypeParameter> methodGenericParams = List.nil();
		List<JCVariableDecl> parameters = List.of(param);
		List<JCExpression> throwsClauses = List.nil();
		JCExpression annotationMethodDefaultValue = null;
		
		List<JCAnnotation> annsOnMethod = copyAnnotations(onMethod);
		if (isFieldDeprecated(field) || deprecate) {
			annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
		}
		
		JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType, methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source.get(), field.getContext());
		copyJavadoc(field, decl, CopyJavadoc.SETTER);
		return decl;
	}
	
	public static java.util.List<String> toAllResolverNames(JavacNode field) {
		return toAllSetterNames(field.getAst(), getAccessorsForField(field), field.getName(), isBoolean(field));
	}
	
	public static java.util.List<String> toAllSetterNames(AST<?, ?, ?> ast, AnnotationValues<Accessors> accessors, CharSequence fieldName, boolean isBoolean) {
		return toAllAccessorNames(ast, accessors, fieldName, isBoolean, RESOLVER_PREFIX, RESOLVER_PREFIX, true);
	}
	
	public static String toResolverName(JavacNode field) {
		return toResolverName(field.getAst(), getAccessorsForField(field), field.getName(), isBoolean(field));
	}
	
	public static String toResolverName(AST<?, ?, ?> ast, AnnotationValues<Accessors> accessors, CharSequence fieldName, boolean isBoolean) {
		return toAccessorName(ast, accessors, fieldName, isBoolean, RESOLVER_PREFIX, RESOLVER_PREFIX, true);
	}
	
	private static java.util.List<String> toAllAccessorNames(AST<?, ?, ?> ast, AnnotationValues<Accessors> accessors, CharSequence fieldName, boolean isBoolean, String booleanPrefix, String normalPrefix, boolean adhereToFluent) {
		
		if (Boolean.TRUE.equals(ast.readConfiguration(ConfigurationKeys.GETTER_CONSEQUENT_BOOLEAN))) isBoolean = false;
		if (!isBoolean) {
			String accessorName = toAccessorName(ast, accessors, fieldName, false, booleanPrefix, normalPrefix, adhereToFluent);
			return (accessorName == null) ? Collections.<String>emptyList() : Collections.singletonList(accessorName);
		}
		
		boolean explicitPrefix = accessors != null && accessors.isExplicit("prefix");
		boolean explicitFluent = accessors != null && accessors.isExplicit("fluent");
		
		Accessors ac = (explicitPrefix || explicitFluent) ? accessors.getInstance() : null;
		
		java.util.List<String> prefix = explicitPrefix ? Arrays.asList(ac.prefix()) : ast.readConfiguration(ConfigurationKeys.ACCESSORS_PREFIX);
		boolean fluent = explicitFluent ? ac.fluent() : Boolean.TRUE.equals(ast.readConfiguration(ConfigurationKeys.ACCESSORS_FLUENT));
		
		fieldName = removePrefix(fieldName, prefix);
		if (fieldName == null) return Collections.emptyList();
		
		java.util.List<String> baseNames = toBaseNames(fieldName, isBoolean, fluent);
		
		Set<String> names = new HashSet<String>();
		for (String baseName : baseNames) {
			if (adhereToFluent && fluent) {
				names.add(baseName);
			} else {
				names.add(buildAccessorName(normalPrefix, baseName));
				if (!normalPrefix.equals(booleanPrefix)) names.add(buildAccessorName(booleanPrefix, baseName));
			}
		}
		
		return new ArrayList<String>(names);
	}
	
	private static java.util.List<String> toBaseNames(CharSequence fieldName, boolean isBoolean, boolean fluent) {
		java.util.List<String> baseNames = new ArrayList<String>();
		baseNames.add(fieldName.toString());
		
		// isPrefix = field is called something like 'isRunning', so 'running'
		// could also be the fieldname.
		String fName = fieldName.toString();
		if (fName.startsWith("is") && fName.length() > 2 && !Character.isLowerCase(fName.charAt(2))) {
			String baseName = fName.substring(2);
			if (fluent) {
				baseNames.add("" + Character.toLowerCase(baseName.charAt(0)) + baseName.substring(1));
			} else {
				baseNames.add(baseName);
			}
		}
		
		return baseNames;
	}
	
	private static String toAccessorName(AST<?, ?, ?> ast, AnnotationValues<Accessors> accessors, CharSequence fieldName, boolean isBoolean, String booleanPrefix, String normalPrefix, boolean adhereToFluent) {
		
		fieldName = fieldName.toString();
		if (fieldName.length() == 0) return null;
		
		if (Boolean.TRUE.equals(ast.readConfiguration(ConfigurationKeys.GETTER_CONSEQUENT_BOOLEAN))) isBoolean = false;
		boolean explicitPrefix = accessors != null && accessors.isExplicit("prefix");
		boolean explicitFluent = accessors != null && accessors.isExplicit("fluent");
		
		Accessors ac = (explicitPrefix || explicitFluent) ? accessors.getInstance() : null;
		
		java.util.List<String> prefix = explicitPrefix ? Arrays.asList(ac.prefix()) : ast.readConfiguration(ConfigurationKeys.ACCESSORS_PREFIX);
		boolean fluent = explicitFluent ? ac.fluent() : Boolean.TRUE.equals(ast.readConfiguration(ConfigurationKeys.ACCESSORS_FLUENT));
		
		fieldName = removePrefix(fieldName, prefix);
		if (fieldName == null) return null;
		
		String fName = fieldName.toString();
		if (adhereToFluent && fluent) return fName;
		
		if (isBoolean && fName.startsWith("is") && fieldName.length() > 2 && !Character.isLowerCase(fieldName.charAt(2))) {
			// The field is for example named 'isRunning'.
			return booleanPrefix + fName.substring(2);
		}
		
		return buildAccessorName(isBoolean ? booleanPrefix : normalPrefix, fName);
	}
}