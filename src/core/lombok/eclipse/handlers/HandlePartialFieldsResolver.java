package lombok.eclipse.handlers;

import static java.util.Arrays.asList;
import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.MemberExistsResult.*;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jdt.internal.compiler.ast.AND_AND_Expression;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.CharLiteral;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.DoubleLiteral;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FalseLiteral;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.FloatLiteral;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.IntLiteral;
import org.eclipse.jdt.internal.compiler.ast.LongLiteral;
import org.eclipse.jdt.internal.compiler.ast.MarkerAnnotation;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OR_OR_Expression;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleMemberAnnotation;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.ConfigurationKeys;
import lombok.NoArgsConstructor;
import lombok.PartialFieldsResolver;
import lombok.RequiredArgsConstructor;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.core.handlers.HandlerUtil.FieldAccess;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.handlers.EclipseHandlerUtil.MemberExistsResult;
import lombok.eclipse.handlers.HandleConstructor.SkipIfConstructorExists;
import lombok.experimental.Accessors;
import lombok.spi.Provides;

/**
 * Handles the {@code lombok.Setter} annotation for eclipse.
 */
@Provides 
public class HandlePartialFieldsResolver extends EclipseAnnotationHandler<PartialFieldsResolver> {

	private static final String INJECTED_STRING_FIELDS_LIST = "$$_partial_fields_list";
	private static final String FIELDS_LIST_FIELD_CONSTRUCTOR_PARAM_NAME = "fields";

	/** Matches the simple part of any annotation that lombok considers as indicative of NonNull status. */
	public static final Pattern NON_NULL_PATTERN = Pattern.compile("^(?:nonnull)$", Pattern.CASE_INSENSITIVE);
	
	/** Matches the simple part of any annotation that lombok considers as indicative of Nullable status. */
	public static final Pattern NULLABLE_PATTERN = Pattern.compile("^(?:nullable|checkfornull)$", Pattern.CASE_INSENSITIVE);
	
	private static final char[] DEFAULT_PREFIX = {'$', 'd', 'e', 'f', 'a', 'u', 'l', 't', '$'};
	
	private static final char[] prefixWith(char[] prefix, char[] name) {
		char[] out = new char[prefix.length + name.length];
		System.arraycopy(prefix, 0, out, 0, prefix.length);
		System.arraycopy(name, 0, out, prefix.length, name.length);
		return out;
	}
	
	private static final char[][] JAVA_BEANS_CONSTRUCTORPROPERTIES = new char[][] {"java".toCharArray(), "beans".toCharArray(), "ConstructorProperties".toCharArray()};
	
	public static Annotation[] createConstructorProperties(ASTNode source, Collection<EclipseNode> fields) {
		if (fields.isEmpty()) return null;
		
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		long[] poss = new long[3];
		Arrays.fill(poss, p);
		QualifiedTypeReference constructorPropertiesType = new QualifiedTypeReference(JAVA_BEANS_CONSTRUCTORPROPERTIES, poss);
		setGeneratedBy(constructorPropertiesType, source);
		SingleMemberAnnotation ann = new SingleMemberAnnotation(constructorPropertiesType, pS);
		ann.declarationSourceEnd = pE;
		
		ArrayInitializer fieldNames = new ArrayInitializer();
		fieldNames.sourceStart = pS;
		fieldNames.sourceEnd = pE;
		fieldNames.expressions = new Expression[fields.size()];
		
		int ctr = 0;
		for (EclipseNode field : fields) {
			char[] fieldName = removePrefixFromField(field);
			fieldNames.expressions[ctr] = new StringLiteral(fieldName, pS, pE, 0);
			setGeneratedBy(fieldNames.expressions[ctr], source);
			ctr++;
		}
		
		ann.memberValue = fieldNames;
		setGeneratedBy(ann, source);
		setGeneratedBy(ann.memberValue, source);
		return new Annotation[] {ann};
	}

	public boolean generateResolversForType(EclipseNode typeNode, EclipseNode pos, AccessLevel level, boolean checkForTypeLevelResolvers, List<Annotation> onMethod, List<Annotation> onParam, List<Annotation> onConstructor, PartialFieldsResolver annotationInstance) {
		if (checkForTypeLevelResolvers) {
			if (hasAnnotation(PartialFieldsResolver.class, typeNode)) {
				// The annotation will make it happen, so we can skip it.
				return true;
			}
		}
		
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) typeDecl = (TypeDeclaration) typeNode.get();
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers & (ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation | ClassFileConstants.AccEnum)) != 0;
		
		if (typeDecl == null || notAClass) {
			pos.addError("@PartialFieldsResolver is only supported on a class.");
			return false;
		}
		
		// Generate list of fields
		FieldDeclaration fieldsListDeclaration = createListFields(pos, annotationInstance);
		EclipseNode fieldsListNode = injectField(typeNode, fieldsListDeclaration);
		
		// Generate constructors
		generateConstructors(typeNode, level, asList(fieldsListNode), false, SkipIfConstructorExists.NO, onConstructor, pos, annotationInstance);
		
		for (EclipseNode field : typeNode.down()) {
			if (field.getKind() != Kind.FIELD) continue;
			
			FieldDeclaration fieldDecl = (FieldDeclaration) field.get();
			if (!filterField(fieldDecl)) continue;
			
			// Skip final fields.
			if ((fieldDecl.modifiers & ClassFileConstants.AccFinal) != 0) continue;
			
			generateResolversForField(field, pos, level, onMethod, onParam, annotationInstance);
		}
		return true;
	}
	
	private static FieldDeclaration createListFields(EclipseNode pos, PartialFieldsResolver annotationInstance) {
		ASTNode source = pos.get();
		
		FieldDeclaration fieldDecl = new FieldDeclaration(INJECTED_STRING_FIELDS_LIST.toCharArray(), 0, -1);
		setGeneratedBy(fieldDecl, source);
		fieldDecl.declarationSourceEnd = -1;
		fieldDecl.modifiers = Modifier.PRIVATE;
		fieldDecl.type = createTypeReference("java.util.List", "java.lang.String", pos);
		if (annotationInstance.addJsonIgnoreOnFieldsList()) {
			
			char[][] cc = fromQualifiedName("com.fasterxml.jackson.annotation.JsonIgnore");
			QualifiedTypeReference qtr = new QualifiedTypeReference(cc, poss(source, cc.length));
			setGeneratedBy(qtr, source);
			MarkerAnnotation jsonIgnoreAnnotation = new MarkerAnnotation(qtr, source.sourceStart);
			jsonIgnoreAnnotation.sourceEnd = source.sourceEnd;
			setGeneratedBy(jsonIgnoreAnnotation, source);
			
			fieldDecl.annotations = new Annotation[] {jsonIgnoreAnnotation};
		}
		
		return fieldDecl;
	}
	
	public static TypeReference createTypeReference(String typeName, EclipseNode sourceNode) {
		ASTNode source = sourceNode.get();
		char[][] typeNameTokens = fromQualifiedName(typeName);
		TypeReference typeReference = new QualifiedTypeReference(typeNameTokens, poss(source, typeNameTokens.length));
		setGeneratedBy(typeReference, source);
		return typeReference;
	}
	
	public static ArrayTypeReference createArrayTypeReference(String typeName, EclipseNode sourceNode) {
		ASTNode source = sourceNode.get();
		long p = ((long) source.sourceStart << 32) | (source.sourceEnd & 0xFFFFFFFFL);
		ArrayTypeReference typeReference = new ArrayTypeReference(typeName.toCharArray(), 1, p);
		setGeneratedBy(typeReference, source);
		return typeReference;
	}
	
	public static TypeReference createTypeReference(String typeName, String genericName, EclipseNode sourceNode) {
		ASTNode source = sourceNode.get();
		char[][] type = fromQualifiedName(typeName);
		char[][] generic = fromQualifiedName(genericName);
		int size = generic.length;
		TypeReference innerType = new QualifiedTypeReference(generic, poss(source, size));
		TypeReference[][] typeParams = new TypeReference[size][];
		typeParams[size - 1] = new TypeReference[] {innerType};
		TypeReference typeRef = new ParameterizedQualifiedTypeReference(type, typeParams, 0, poss(source, size));
		setGeneratedBy(typeRef, source);
		return typeRef;
	}
	
	private void generateConstructors(EclipseNode typeNode, AccessLevel level, List<EclipseNode> fieldsToParam, boolean forceDefaults, SkipIfConstructorExists skipIfConstructorExists, List<Annotation> onConstructor, EclipseNode sourceNode, PartialFieldsResolver annotationInstance) {
		
		if (skipIfConstructorExists != SkipIfConstructorExists.NO) {
			for (EclipseNode child : typeNode.down()) {
				if (child.getKind() == Kind.ANNOTATION) {
					boolean skipGeneration = (annotationTypeMatches(NoArgsConstructor.class, child) || annotationTypeMatches(AllArgsConstructor.class, child) || annotationTypeMatches(RequiredArgsConstructor.class, child));
					
					if (!skipGeneration && skipIfConstructorExists == SkipIfConstructorExists.YES) {
						skipGeneration = annotationTypeMatches(Builder.class, child);
					}
					
					if (skipGeneration) {
						return;
					}
				}
			}
		}
		
		if (!(skipIfConstructorExists != SkipIfConstructorExists.NO && constructorExists(typeNode) != MemberExistsResult.NOT_EXISTS)) {
			ConstructorDeclaration constr = createConstructor(AccessLevel.PUBLIC, typeNode, fieldsToParam, forceDefaults, sourceNode, onConstructor);
			injectMethod(typeNode, constr);
			
			if (annotationInstance.buildAdicionalFieldsListStringArrayConstructor()) {
				ConstructorDeclaration constrStringArray = createConstructorStringArray(AccessLevel.PUBLIC, typeNode, fieldsToParam, forceDefaults, sourceNode, onConstructor);
				injectMethod(typeNode, constrStringArray);
			}
		}
	}
	
	private static List<EclipseNode> fieldsNeedingBuilderDefaults(EclipseNode type, Collection<EclipseNode> fieldsToParam) {
		List<EclipseNode> out = new ArrayList<EclipseNode>();
		top: for (EclipseNode node : type.down()) {
			if (node.getKind() != Kind.FIELD) continue top;
			FieldDeclaration fd = (FieldDeclaration) node.get();
			if ((fd.modifiers & ClassFileConstants.AccStatic) != 0) continue top;
			for (EclipseNode ftp : fieldsToParam)
				if (node == ftp) continue top;
			if (EclipseHandlerUtil.hasAnnotation(Builder.Default.class, node)) out.add(node);
		}
		return out;
	}
	
	private static List<EclipseNode> fieldsNeedingExplicitDefaults(EclipseNode type, Collection<EclipseNode> fieldsToParam) {
		List<EclipseNode> out = new ArrayList<EclipseNode>();
		top: for (EclipseNode node : type.down()) {
			if (node.getKind() != Kind.FIELD) continue top;
			FieldDeclaration fd = (FieldDeclaration) node.get();
			if (fd.initialization != null) continue top;
			if ((fd.modifiers & ClassFileConstants.AccFinal) == 0) continue top;
			if ((fd.modifiers & ClassFileConstants.AccStatic) != 0) continue top;
			for (EclipseNode ftp : fieldsToParam)
				if (node == ftp) continue top;
			if (EclipseHandlerUtil.hasAnnotation(Builder.Default.class, node)) continue top;
			out.add(node);
		}
		return out;
	}
	
	private static Expression getDefaultExpr(TypeReference type, int s, int e) {
		boolean array = type instanceof ArrayTypeReference;
		if (array) return new NullLiteral(s, e);
		char[] lastToken = type.getLastToken();
		if (Arrays.equals(TypeConstants.BOOLEAN, lastToken)) return new FalseLiteral(s, e);
		if (Arrays.equals(TypeConstants.CHAR, lastToken)) return new CharLiteral(new char[] {'\'', '\\', '0', '\''}, s, e);
		if (Arrays.equals(TypeConstants.BYTE, lastToken) || Arrays.equals(TypeConstants.SHORT, lastToken) || Arrays.equals(TypeConstants.INT, lastToken)) return IntLiteral.buildIntLiteral(new char[] {'0'}, s, e);
		if (Arrays.equals(TypeConstants.LONG, lastToken)) return LongLiteral.buildLongLiteral(new char[] {'0', 'L'}, s, e);
		if (Arrays.equals(TypeConstants.FLOAT, lastToken)) return new FloatLiteral(new char[] {'0', 'F'}, s, e);
		if (Arrays.equals(TypeConstants.DOUBLE, lastToken)) return new DoubleLiteral(new char[] {'0', 'D'}, s, e);
		
		return new NullLiteral(s, e);
	}
	
	public static ConstructorDeclaration createConstructor(AccessLevel level, EclipseNode type, Collection<EclipseNode> fieldsToParam, boolean forceDefaults, EclipseNode sourceNode, List<Annotation> onConstructor) {
		
		ASTNode source = sourceNode.get();
		TypeDeclaration typeDeclaration = ((TypeDeclaration) type.get());
		long p = (long) source.sourceStart << 32 | source.sourceEnd;
		
		boolean isEnum = (((TypeDeclaration) type.get()).modifiers & ClassFileConstants.AccEnum) != 0;
		
		if (isEnum) level = AccessLevel.PRIVATE;
		
		List<EclipseNode> fieldsToDefault = fieldsNeedingBuilderDefaults(type, fieldsToParam);
		List<EclipseNode> fieldsToExplicit = forceDefaults ? fieldsNeedingExplicitDefaults(type, fieldsToParam) : Collections.<EclipseNode>emptyList();
		
		boolean addConstructorProperties;
		if (fieldsToParam.isEmpty()) {
			addConstructorProperties = false;
		} else {
			Boolean v = type.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_ADD_CONSTRUCTOR_PROPERTIES);
			addConstructorProperties = v != null ? v.booleanValue() : Boolean.FALSE.equals(type.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_SUPPRESS_CONSTRUCTOR_PROPERTIES));
		}
		
		ConstructorDeclaration constructor = new ConstructorDeclaration(((CompilationUnitDeclaration) type.top().get()).compilationResult);
		
		constructor.modifiers = toEclipseModifier(level);
		constructor.selector = typeDeclaration.name;
		constructor.constructorCall = new ExplicitConstructorCall(ExplicitConstructorCall.ImplicitSuper);
		constructor.constructorCall.sourceStart = source.sourceStart;
		constructor.constructorCall.sourceEnd = source.sourceEnd;
		constructor.thrownExceptions = null;
		constructor.typeParameters = null;
		constructor.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		constructor.bodyStart = constructor.declarationSourceStart = constructor.sourceStart = source.sourceStart;
		constructor.bodyEnd = constructor.declarationSourceEnd = constructor.sourceEnd = source.sourceEnd;
		constructor.arguments = null;
		
		List<Argument> params = new ArrayList<Argument>();
		List<Statement> assigns = new ArrayList<Statement>();
		List<Statement> nullChecks = new ArrayList<Statement>();
		
		for (EclipseNode fieldNode : fieldsToParam) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] rawName = field.name;
			char[] fieldName = FIELDS_LIST_FIELD_CONSTRUCTOR_PARAM_NAME.toCharArray();// removePrefixFromField(fieldNode);
			FieldReference thisX = new FieldReference(rawName, p);
			int s = (int) (p >> 32);
			int e = (int) p;
			thisX.receiver = new ThisReference(s, e);
			
			Expression assignmentExpr = new SingleNameReference(fieldName, p);
			
			Assignment assignment = new Assignment(thisX, assignmentExpr, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			assigns.add(assignment);
			long fieldPos = (((long) field.sourceStart) << 32) | field.sourceEnd;
			Argument parameter = new Argument(fieldName, fieldPos, copyType(field.type, source), Modifier.FINAL);
			Annotation[] copyableAnnotations = findCopyableAnnotations(fieldNode);
			if (hasNonNullAnnotations(fieldNode)) {
				Statement nullCheck = generateNullCheck(field.type, field.name, sourceNode, null);
				if (nullCheck != null) nullChecks.add(nullCheck);
			}
			parameter.annotations = copyAnnotations(source, copyableAnnotations);
			params.add(parameter);
		}
		
		for (EclipseNode fieldNode : fieldsToExplicit) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] rawName = field.name;
			FieldReference thisX = new FieldReference(rawName, p);
			int s = (int) (p >> 32);
			int e = (int) p;
			thisX.receiver = new ThisReference(s, e);
			
			Expression assignmentExpr = getDefaultExpr(field.type, s, e);
			
			Assignment assignment = new Assignment(thisX, assignmentExpr, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			assigns.add(assignment);
		}
		
		for (EclipseNode fieldNode : fieldsToDefault) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] rawName = field.name;
			FieldReference thisX = new FieldReference(rawName, p);
			int s = (int) (p >> 32);
			int e = (int) p;
			thisX.receiver = new ThisReference(s, e);
			
			MessageSend inv = new MessageSend();
			inv.sourceStart = source.sourceStart;
			inv.sourceEnd = source.sourceEnd;
			inv.receiver = new SingleNameReference(((TypeDeclaration) type.get()).name, 0L);
			inv.selector = prefixWith(DEFAULT_PREFIX, removePrefixFromField(fieldNode));
			
			Assignment assignment = new Assignment(thisX, inv, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			assigns.add(assignment);
		}
		
		nullChecks.addAll(assigns);
		constructor.statements = nullChecks.isEmpty() ? null : nullChecks.toArray(new Statement[0]);
		constructor.arguments = params.isEmpty() ? null : params.toArray(new Argument[0]);
		
		/*
		 * Generate annotations that must be put on the generated method, and
		 * attach them.
		 */ {
			Annotation[] constructorProperties = null, checkerFramework = null;
			if (addConstructorProperties && !isLocalType(type)) constructorProperties = createConstructorProperties(source, fieldsToParam);
			if (getCheckerFrameworkVersion(type).generateUnique()) checkerFramework = new Annotation[] {generateNamedAnnotation(source, CheckerFrameworkVersion.NAME__UNIQUE)};
			
			constructor.annotations = copyAnnotations(source, onConstructor.toArray(new Annotation[0]), constructorProperties, checkerFramework);
		}
		
		constructor.traverse(new SetGeneratedByVisitor(source), typeDeclaration.scope);
		return constructor;
	}
	
	public static ConstructorDeclaration createConstructorStringArray(AccessLevel level, EclipseNode type, Collection<EclipseNode> fieldsToParam, boolean forceDefaults, EclipseNode sourceNode, List<Annotation> onConstructor) {
		
		ASTNode source = sourceNode.get();
		TypeDeclaration typeDeclaration = ((TypeDeclaration) type.get());
		long p = (long) source.sourceStart << 32 | source.sourceEnd;
		
		boolean isEnum = (((TypeDeclaration) type.get()).modifiers & ClassFileConstants.AccEnum) != 0;
		
		if (isEnum) level = AccessLevel.PRIVATE;
		
		List<EclipseNode> fieldsToDefault = fieldsNeedingBuilderDefaults(type, fieldsToParam);
		List<EclipseNode> fieldsToExplicit = forceDefaults ? fieldsNeedingExplicitDefaults(type, fieldsToParam) : Collections.<EclipseNode>emptyList();
		
		boolean addConstructorProperties;
		if (fieldsToParam.isEmpty()) {
			addConstructorProperties = false;
		} else {
			Boolean v = type.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_ADD_CONSTRUCTOR_PROPERTIES);
			addConstructorProperties = v != null ? v.booleanValue() : Boolean.FALSE.equals(type.getAst().readConfiguration(ConfigurationKeys.ANY_CONSTRUCTOR_SUPPRESS_CONSTRUCTOR_PROPERTIES));
		}
		
		ConstructorDeclaration constructor = new ConstructorDeclaration(((CompilationUnitDeclaration) type.top().get()).compilationResult);
		
		constructor.modifiers = toEclipseModifier(level);
		constructor.selector = typeDeclaration.name;
		constructor.constructorCall = new ExplicitConstructorCall(ExplicitConstructorCall.ImplicitSuper);
		constructor.constructorCall.sourceStart = source.sourceStart;
		constructor.constructorCall.sourceEnd = source.sourceEnd;
		constructor.thrownExceptions = null;
		constructor.typeParameters = null;
		constructor.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		constructor.bodyStart = constructor.declarationSourceStart = constructor.sourceStart = source.sourceStart;
		constructor.bodyEnd = constructor.declarationSourceEnd = constructor.sourceEnd = source.sourceEnd;
		constructor.arguments = null;
		
		List<Argument> params = new ArrayList<Argument>();
		List<Statement> assigns = new ArrayList<Statement>();
		List<Statement> nullChecks = new ArrayList<Statement>();
		
		for (EclipseNode fieldNode : fieldsToParam) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] rawName = field.name;
			char[] fieldName = FIELDS_LIST_FIELD_CONSTRUCTOR_PARAM_NAME.toCharArray();// removePrefixFromField(fieldNode);
			FieldReference thisX = new FieldReference(rawName, p);
			int s = (int) (p >> 32);
			int e = (int) p;
			thisX.receiver = new ThisReference(s, e);
			
			// java.util.Arrays.asList(fieldName)
			char[][] JAVA_UTIL_ARRAYS = {"java".toCharArray(), "util".toCharArray(), "Arrays".toCharArray()};
			long[] NULL_POSS = {0L};
			MessageSend assignmentExpr = new MessageSend();
			assignmentExpr.receiver = new QualifiedNameReference(JAVA_UTIL_ARRAYS, NULL_POSS, s, e);
			assignmentExpr.selector = "asList".toCharArray();
			assignmentExpr.arguments = new Expression[] {new SingleNameReference(fieldName, p)};
			
			Assignment assignment = new Assignment(thisX, assignmentExpr, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			assigns.add(assignment);
			long fieldPos = (((long) field.sourceStart) << 32) | field.sourceEnd;
			Argument parameter = new Argument(fieldName, fieldPos, createArrayTypeReference("String", fieldNode), Modifier.FINAL);
			Annotation[] copyableAnnotations = findCopyableAnnotations(fieldNode);
			if (hasNonNullAnnotations(fieldNode)) {
				Statement nullCheck = generateNullCheck(field.type, field.name, sourceNode, null);
				if (nullCheck != null) nullChecks.add(nullCheck);
			}
			parameter.annotations = copyAnnotations(source, copyableAnnotations);
			params.add(parameter);
		}
		
		for (EclipseNode fieldNode : fieldsToExplicit) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] rawName = field.name;
			FieldReference thisX = new FieldReference(rawName, p);
			int s = (int) (p >> 32);
			int e = (int) p;
			thisX.receiver = new ThisReference(s, e);
			
			Expression assignmentExpr = getDefaultExpr(field.type, s, e);
			
			Assignment assignment = new Assignment(thisX, assignmentExpr, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			assigns.add(assignment);
		}
		
		for (EclipseNode fieldNode : fieldsToDefault) {
			FieldDeclaration field = (FieldDeclaration) fieldNode.get();
			char[] rawName = field.name;
			FieldReference thisX = new FieldReference(rawName, p);
			int s = (int) (p >> 32);
			int e = (int) p;
			thisX.receiver = new ThisReference(s, e);
			
			MessageSend inv = new MessageSend();
			inv.sourceStart = source.sourceStart;
			inv.sourceEnd = source.sourceEnd;
			inv.receiver = new SingleNameReference(((TypeDeclaration) type.get()).name, 0L);
			inv.selector = prefixWith(DEFAULT_PREFIX, removePrefixFromField(fieldNode));
			
			Assignment assignment = new Assignment(thisX, inv, (int) p);
			assignment.sourceStart = (int) (p >> 32);
			assignment.sourceEnd = assignment.statementEnd = (int) (p >> 32);
			assigns.add(assignment);
		}
		
		nullChecks.addAll(assigns);
		constructor.statements = nullChecks.isEmpty() ? null : nullChecks.toArray(new Statement[0]);
		constructor.arguments = params.isEmpty() ? null : params.toArray(new Argument[0]);
		
		/*
		 * Generate annotations that must be put on the generated method, and
		 * attach them.
		 */ {
			Annotation[] constructorProperties = null, checkerFramework = null;
			if (addConstructorProperties && !isLocalType(type)) constructorProperties = createConstructorProperties(source, fieldsToParam);
			if (getCheckerFrameworkVersion(type).generateUnique()) checkerFramework = new Annotation[] {generateNamedAnnotation(source, CheckerFrameworkVersion.NAME__UNIQUE)};
			
			constructor.annotations = copyAnnotations(source, onConstructor.toArray(new Annotation[0]), constructorProperties, checkerFramework);
		}
		
		constructor.traverse(new SetGeneratedByVisitor(source), typeDeclaration.scope);
		return constructor;
	}
	
	public static boolean isLocalType(EclipseNode type) {
		Kind kind = type.up().getKind();
		if (kind == Kind.COMPILATION_UNIT) return false;
		if (kind == Kind.TYPE) return isLocalType(type.up());
		return true;
	}
	
	/**
	 * Generates a Resolvers on the stated field.
	 * 
	 * Used by {@link HandleData}.
	 * 
	 * The difference between this call and the handle method is as follows:
	 * 
	 * If there is a {@code lombok.Resolvers} annotation on the field, it is
	 * used and the same rules apply (e.g. warning if the method already exists,
	 * stated access level applies). If not, the Resolvers is still generated if
	 * it isn't already there, though there will not be a warning if its already
	 * there. The default access level is used.
	 * 
	 * @param annotationInstance
	 * @param fieldsListDeclaration
	 */
	public void generateResolversForField(EclipseNode fieldNode, EclipseNode sourceNode, AccessLevel level, List<Annotation> onMethod, List<Annotation> onParam, PartialFieldsResolver annotationInstance) {
		if (hasAnnotation(PartialFieldsResolver.class, fieldNode)) {
			// The annotation will make it happen, so we can skip it.
			return;
		}
		createResolversForField(level, fieldNode, sourceNode, false, onMethod, onParam, annotationInstance);
	}
	
	@Override public void handle(AnnotationValues<PartialFieldsResolver> annotation, Annotation ast, EclipseNode annotationNode) {
		EclipseNode node = annotationNode.up();
		PartialFieldsResolver annotationInstance = annotation.getInstance();
		AccessLevel level = annotationInstance.level();
		if (level == AccessLevel.NONE || node == null) return;
		
		List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@PartialFieldsResolver(onMethod", annotationNode);
		List<Annotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@PartialFieldsResolver(onParam", annotationNode);
		List<Annotation> onConstructor = unboxAndRemoveAnnotationParameter(ast, "onConstructor", "@PartialFieldsResolver(onConstructor", annotationNode);
		
		generateResolversForType(node, annotationNode, level, false, onMethod, onParam, onConstructor, annotationInstance);
	}
	
	public void createResolversForField(AccessLevel level, EclipseNode fieldNode, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam, PartialFieldsResolver annotationInstance) {
		
		if (fieldNode.getKind() != Kind.FIELD) {
			sourceNode.addError("@PartialFieldsResolver is only supported on a class or a field.");
			return;
		}
		
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		String resolverName = toResolverName(fieldNode);
		AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
		boolean shouldReturnThis = shouldReturnThis(fieldNode, accessors);
		
		if (resolverName == null) {
			fieldNode.addWarning("Not generating resolver for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		MemberExistsResult existsReturn = methodExists(resolverName, fieldNode, false, 1);
		if (existsReturn == EXISTS_BY_LOMBOK) {
			return;
		}
		if (existsReturn == EXISTS_BY_USER) {
			if (whineIfExists) {
				fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists", resolverName));
			}
			return;
		}
		
		MethodDeclaration method = createResolver((TypeDeclaration) fieldNode.up().get(), false, fieldNode, resolverName, null, shouldReturnThis, modifier, sourceNode, onMethod, onParam, annotationInstance);
		injectMethod(fieldNode.up(), method);
	}
	
	static MethodDeclaration createResolver(TypeDeclaration parent, boolean deprecate, EclipseNode fieldNode, String name, char[] booleanFieldToSet, boolean shouldReturnThis, int modifier, EclipseNode sourceNode, List<Annotation> onMethod, List<Annotation> onParam, PartialFieldsResolver annotationInstance) {
		ASTNode source = sourceNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		
		TypeReference returnType = null;
		ReturnStatement returnThis = null;
		if (shouldReturnThis) {
			returnType = cloneSelfType(fieldNode, source);
			ThisReference thisRef = new ThisReference(pS, pE);
			returnThis = new ReturnStatement(thisRef, pS, pE);
		}
		
		return createResolver(parent, deprecate, fieldNode, name, booleanFieldToSet, returnType, returnThis, modifier, sourceNode, onMethod, onParam, annotationInstance);
	}
	
	static MethodDeclaration createResolver(TypeDeclaration parent, boolean deprecate, EclipseNode fieldNode, String name, char[] booleanFieldToSet, TypeReference returnType, Statement returnStatement, int modifier, EclipseNode sourceNode, List<Annotation> onMethod, List<Annotation> onParam, PartialFieldsResolver annotationInstance) {
		String fieldName = fieldNode.getName().toString();
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		ASTNode source = sourceNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		if (returnType != null) {
			method.returnType = returnType;
		} else {
			method.returnType = TypeReference.baseTypeReference(TypeIds.T_void, 0);
			method.returnType.sourceStart = pS;
			method.returnType.sourceEnd = pE;
		}
		Annotation[] deprecated = null;
		if (isFieldDeprecated(fieldNode) || deprecate) {
			deprecated = new Annotation[] {generateDeprecatedAnnotation(source)};
		}
		method.annotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), deprecated);
		
		char[][] SUPPLIER = {"java".toCharArray(), "util".toCharArray(), "function".toCharArray(), "Supplier".toCharArray()};
		TypeReference innerType = copyType(field.type, source);
		TypeReference[][] typeParams = new TypeReference[4][];
		typeParams[3] = new TypeReference[] {innerType};
		TypeReference type = new ParameterizedQualifiedTypeReference(SUPPLIER, typeParams, 0, poss(source, 4));
		
		Argument param = new Argument(field.name, p, type, Modifier.FINAL);
		param.sourceStart = pS;
		param.sourceEnd = pE;
		method.arguments = new Argument[] {param};
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		
		MessageSend createBuilderInvoke = new MessageSend();
		createBuilderInvoke.receiver = new SingleNameReference(field.name, 0L);
		createBuilderInvoke.selector = "get".toCharArray();
		
		// NameReference fieldNameRef = new SingleNameReference((new
		// String(field.name) + ".resolve()").toCharArray(), p);
		Assignment assignment = new Assignment(fieldRef, createBuilderInvoke, (int) p);
		assignment.sourceStart = pS;
		assignment.sourceEnd = assignment.statementEnd = pE;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		Annotation[] nonNulls = findAnnotations(field, NON_NULL_PATTERN);
		Annotation[] nullables = findAnnotations(field, NULLABLE_PATTERN);
		
		char[] valueName = INJECTED_STRING_FIELDS_LIST.toCharArray();
		
		boolean resolveMethodsIfFieldsListIsNullOrEmpty = annotationInstance.resolveMethodsIfFieldsListIsNullOrEmpty();
		
		Expression ifExpression;
		if (resolveMethodsIfFieldsListIsNullOrEmpty) {
			// this._fields == null
			EqualExpression isListNull = new EqualExpression(new SingleNameReference(valueName, p), new NullLiteral(pS, pE), BinaryExpression.EQUAL_EQUAL);
			
			// this._fields.isEmpty()
			MessageSend isListEmpty = new MessageSend();
			isListEmpty.receiver = new SingleNameReference(valueName, p);
			isListEmpty.selector = "isEmpty".toCharArray();
			
			// this._fields.contains("fieldName")
			MessageSend isContainsFieldName = new MessageSend();
			isContainsFieldName.receiver = new SingleNameReference(valueName, p);
			isContainsFieldName.selector = "contains".toCharArray();
			isContainsFieldName.arguments = new Expression[] {new StringLiteral(fieldName.toCharArray(), pS, pE, 0)};
			
			// (this._fields == null) || (this._fields.isEmpty())
			OR_OR_Expression comb = new OR_OR_Expression(isListNull, isListEmpty, OperatorIds.OR_OR);
			
			// (this._fields == null) || (this._fields.isEmpty()) ||
			// (this._fields.)
			ifExpression = new OR_OR_Expression(comb, isContainsFieldName, OperatorIds.OR_OR);
			
		} else {
			// this._fields != null
			EqualExpression isListNotNull = new EqualExpression(new SingleNameReference(valueName, p), new NullLiteral(pS, pE), BinaryExpression.NOT_EQUAL);
			
			// this._fields.contains("fieldName")
			MessageSend isContainsFieldName = new MessageSend();
			isContainsFieldName.receiver = new SingleNameReference(valueName, p);
			isContainsFieldName.selector = "contains".toCharArray();
			isContainsFieldName.arguments = new Expression[] {new StringLiteral(fieldName.toCharArray(), pS, pE, 0)};
			
			// (this._fields != null) && (this._fields.contains("fieldName"))
			AND_AND_Expression andExpression = new AND_AND_Expression(isListNotNull, isContainsFieldName, OperatorIds.AND_AND);
			
			ifExpression = andExpression;
		}
		
		Block then = new Block(0);
		
		List<Statement> innerStatements = new ArrayList<Statement>(5);
		if (nonNulls.length == 0) {
			innerStatements.add(assignment);
		} else {
			Statement nullCheck = generateNullCheck(field.type, field.name, sourceNode, null);
			if (nullCheck != null) innerStatements.add(nullCheck);
			innerStatements.add(assignment);
		}
		
		if (booleanFieldToSet != null) {
			innerStatements.add(new Assignment(new SingleNameReference(booleanFieldToSet, p), new TrueLiteral(pS, pE), pE));
		}
		
		if (returnType != null && returnStatement != null) {
			innerStatements.add(returnStatement);
		}
		then.statements = innerStatements.toArray(new Statement[0]);
		IfStatement ifStatement = new IfStatement(ifExpression, then, pS, pE);
		
		method.statements = new Statement[] {ifStatement};
		param.annotations = copyAnnotations(source, nonNulls, nullables, onParam.toArray(new Annotation[0]));
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
	private String toResolverName(EclipseNode field) {
		String fieldName = field.getName().toString();
		if (fieldName.length() == 0) return null;
		return buildAccessorName("resolve", fieldName);
	}
	
	/**
	 * @param prefix
	 *            Something like {@code get} or {@code set} or {@code is}.
	 * @param suffix
	 *            Something like {@code running}.
	 * @return prefix + smartly title-cased suffix. For example,
	 *         {@code setRunning}.
	 */
	public static String buildAccessorName(String prefix, String suffix) {
		if (suffix.length() == 0) return prefix;
		if (prefix.length() == 0) return suffix;
		
		char first = suffix.charAt(0);
		if (Character.isLowerCase(first)) {
			boolean useUpperCase = suffix.length() > 2 && (Character.isTitleCase(suffix.charAt(1)) || Character.isUpperCase(suffix.charAt(1)));
			suffix = String.format("%s%s", useUpperCase ? Character.toUpperCase(first) : Character.toTitleCase(first), suffix.subSequence(1, suffix.length()));
		}
		return String.format("%s%s", prefix, suffix);
	}
}