package io.joern.javasrc2cpg.typesolvers

import com.github.javaparser.ast.`type`.{PrimitiveType, Type}
import com.github.javaparser.resolution.SymbolResolver
import com.github.javaparser.resolution.declarations.{
  ResolvedDeclaration,
  ResolvedTypeDeclaration,
  ResolvedTypeParameterDeclaration
}
import com.github.javaparser.resolution.types._
import com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap
import com.github.javaparser.resolution.types.{
  ResolvedArrayType,
  ResolvedLambdaConstraintType,
  ResolvedPrimitiveType,
  ResolvedReferenceType,
  ResolvedType,
  ResolvedTypeVariable,
  ResolvedUnionType,
  ResolvedVoidType,
  ResolvedWildcard
}
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration
import com.github.javaparser.symbolsolver.logic.InferenceVariableType
import com.github.javaparser.symbolsolver.model.typesystem.{LazyType, NullType}
import io.joern.javasrc2cpg.typesolvers.TypeInfoCalculator.{TypeConstants, TypeNameConstants}
import io.joern.x2cpg.datastructures.Global
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOptional
import scala.util.Try

class TypeInfoCalculator(global: Global, symbolResolver: SymbolResolver) {
  private val logger               = LoggerFactory.getLogger(this.getClass)
  private val emptyTypeParamValues = ResolvedTypeParametersMap.empty()

  def name(typ: ResolvedType): Option[String] = {
    nameOrFullName(typ, emptyTypeParamValues, fullyQualified = false)
  }

  def name(typ: ResolvedType, typeParamValues: ResolvedTypeParametersMap): Option[String] = {
    nameOrFullName(typ, typeParamValues, fullyQualified = false)
  }

  def fullName(typ: ResolvedType): Option[String] = {
    nameOrFullName(typ, emptyTypeParamValues, fullyQualified = true).map(registerType)
  }

  def fullName(typ: ResolvedType, typeParamValues: ResolvedTypeParametersMap): Option[String] = {
    nameOrFullName(typ, typeParamValues, fullyQualified = true).map(registerType)
  }

  private def typesSubstituted(
    trySubstitutedType: Try[ResolvedType],
    typeParamDecl: ResolvedTypeParameterDeclaration
  ): Boolean = {
    trySubstitutedType
      .map { substitutedType =>
        // substitutedType.isTypeVariable can crash with an UnsolvedSymbolException if it is an instance of LazyType,
        // in which case the type hasn't been successfully substituted.
        val substitutionOccurred =
          !(substitutedType.isTypeVariable && substitutedType.asTypeParameter() == typeParamDecl)
        // There's a potential infinite loop that can occur when a type variable is substituted with a wildcard type
        // bounded by that type variable.
        val isSimilarWildcardSubstition = substitutedType match {
          case wc: ResolvedWildcard => Try(wc.getBoundedType.asTypeParameter()).toOption.contains(typeParamDecl)
          case _                    => false
        }

        substitutionOccurred && !isSimilarWildcardSubstition
      }
      .getOrElse(false)
  }

  private def nameOrFullName(
    typ: ResolvedType,
    typeParamValues: ResolvedTypeParametersMap,
    fullyQualified: Boolean
  ): Option[String] = {
    typ match {
      case refType: ResolvedReferenceType =>
        nameOrFullName(refType.getTypeDeclaration.get, fullyQualified)
      case lazyType: LazyType =>
        lazyType match {
          case _
              if Try(lazyType.isReferenceType).toOption.isDefined
                && Try(lazyType.isReferenceType).toOption.get =>
            nameOrFullName(lazyType.asReferenceType(), typeParamValues, fullyQualified)
          case _
              if Try(lazyType.isTypeVariable).toOption.isDefined
                && Try(lazyType.isTypeVariable).toOption.get =>
            nameOrFullName(lazyType.asTypeVariable(), typeParamValues, fullyQualified)
          case _
              if Try(lazyType.isArray).toOption.isDefined
                && Try(lazyType.isArray).toOption.get =>
            nameOrFullName(lazyType.asArrayType(), typeParamValues, fullyQualified)
          case _ if Try(lazyType.isPrimitive).toOption.isDefined && Try(lazyType.isPrimitive).get =>
            nameOrFullName(lazyType.asPrimitive(), typeParamValues, fullyQualified)
          case _ if Try(lazyType.isWildcard).toOption.isDefined && Try(lazyType.isWildcard).get =>
            nameOrFullName(lazyType.asWildcard(), typeParamValues, fullyQualified)
          case _ =>
            objectType(fullyQualified)
        }
      case tpe @ (_: ResolvedVoidType | _: ResolvedPrimitiveType) =>
        Some(tpe.describe())
      case arrayType: ResolvedArrayType =>
        nameOrFullName(arrayType.getComponentType, typeParamValues, fullyQualified).map(_ + "[]")
      case nullType: NullType =>
        Some(nullType.describe())
      case typeVariable: ResolvedTypeVariable =>
        /*
        val typeParamDecl      = typeVariable.asTypeParameter()
        val substitutedTypeOpt = Try(typeParamValues.getValue(typeParamDecl)).toOption
        // This is the way the library tells us there is no substitution happened.
        // Also, prevent infinite looping with the equals check.
        if (substitutedTypeOpt.isDefined && !typ.equals(substitutedTypeOpt.get)) {
          val extendsBoundOption = Try(typeParamDecl.getBounds.asScala.find(_.isExtends)).toOption
          val isTypeVarOpt       = Try(substitutedTypeOpt.get.isTypeVariable).toOption
          if (
            extendsBoundOption.isDefined
            && isTypeVarOpt.isDefined && isTypeVarOpt.get
            && substitutedTypeOpt.get.asTypeParameter() == typeParamDecl
          ) {
            extendsBoundOption.get
              .map(bound => nameOrFullName(bound.getType, typeParamValues, fullyQualified))
              .getOrElse(objectType(fullyQualified))
          } else {
            nameOrFullName(substitutedTypeOpt.get, typeParamValues, fullyQualified)
          }
        } else {
          objectType(fullyQualified)
         */
        val typeParamDecl   = typeVariable.asTypeParameter()
        val substitutedType = Try(typeParamValues.getValue(typeParamDecl))

        if (typesSubstituted(substitutedType, typeParamDecl)) {
          nameOrFullName(substitutedType.get, typeParamValues, fullyQualified)
        } else {
          val extendsBoundOption = Try(typeParamDecl.getBounds.asScala.find(_.isExtends)).toOption.flatten
          extendsBoundOption
            .flatMap(bound => nameOrFullName(bound.getType, typeParamValues, fullyQualified))
            .orElse(objectType(fullyQualified))
        }
      case lambdaConstraintType: ResolvedLambdaConstraintType =>
        nameOrFullName(lambdaConstraintType.getBound, typeParamValues, fullyQualified)
      case wildcardType: ResolvedWildcard =>
        if (wildcardType.isBounded) {
          nameOrFullName(wildcardType.getBoundedType, typeParamValues, fullyQualified)
        } else {
          objectType(fullyQualified)
        }
      case unionType: ResolvedUnionType =>
        // The individual elements of the type union cannot be accessed in ResolvedUnionType.
        // For whatever reason there is no accessor and the field is private.
        // So for now we settle with the ancestor type. Maybe we use reflection later.
        /*
        val ancestorOptionOpt = Try(unionType.getCommonAncestor).toOption
        if (ancestorOptionOpt.isDefined && ancestorOptionOpt.get.isPresent) {
          nameOrFullName(ancestorOptionOpt.get.get, typeParamValues, fullyQualified)
        } else {
          objectType(fullyQualified)
        }
         */
        Try(unionType.getCommonAncestor.toScala).toOption.flatten
          .flatMap(nameOrFullName(_, typeParamValues, fullyQualified))
          .orElse(objectType(fullyQualified))
      case _: InferenceVariableType =>
        // From the JavaParser docs, the InferenceVariableType is: An element using during type inference.
        // At this point JavaParser has failed to resolve the type.
        None
    }
  }

  private def objectType(fullyQualified: Boolean): Option[String] = {
    // Return an option type for
    if (fullyQualified) {
      Some(TypeConstants.Object)
    } else {
      Some(TypeNameConstants.Object)
    }
  }

  def name(typ: Type): Option[String] = {
    nameOrFullName(typ, fullyQualified = false)
  }

  def fullName(typ: Type): Option[String] = {
    nameOrFullName(typ, fullyQualified = true).map(registerType)
  }

  private def nameOrFullName(typ: Type, fullyQualified: Boolean): Option[String] = {
    typ match {
      case primitiveType: PrimitiveType =>
        Some(primitiveType.toString)
      case _ =>
        // We are using symbolResolver.toResolvedType() instead of typ.resolve() because
        // the resolve() is just a wrapper for a call to symbolResolver.toResolvedType()
        // with a specific class given as argument to which the result is casted to.
        // It appears to be that ClassOrInterfaceType.resolve() is using a too restrictive
        // bound (ResolvedReferenceType.class) which invalidates an otherwise successful
        // resolve. Since we anyway dont care about the type cast, we directly access the
        // symbolResolver and specifiy the most generic type ResolvedType.
        Try(symbolResolver.toResolvedType(typ, classOf[ResolvedType])).toOption
          .flatMap(resolvedType => nameOrFullName(resolvedType, emptyTypeParamValues, fullyQualified))
    }
  }

  def name(decl: ResolvedDeclaration): Option[String] = {
    nameOrFullName(decl, fullyQualified = false)
  }

  def fullName(decl: ResolvedDeclaration): Option[String] = {
    nameOrFullName(decl, fullyQualified = true).map(registerType)
  }

  private def nameOrFullName(decl: ResolvedDeclaration, fullyQualified: Boolean): Option[String] = {
    decl match {
      case typeDecl: ResolvedTypeDeclaration =>
        nameOrFullName(typeDecl, fullyQualified)
    }
  }

  private def nameOrFullName(typeDecl: ResolvedTypeDeclaration, fullyQualified: Boolean): Option[String] = {
    typeDecl match {
      case typeParamDecl: ResolvedTypeParameterDeclaration =>
        if (fullyQualified) {
          val containFullName =
            nameOrFullName(typeParamDecl.getContainer.asInstanceOf[ResolvedDeclaration], fullyQualified = true)
          containFullName.map(_ + "." + typeParamDecl.getName)
        } else {
          Some(typeParamDecl.getName)
        }
      case _ =>
        val typeName = Option(typeDecl.getName).getOrElse(throw new RuntimeException("TODO Investigate"))

        // TODO Sadly we need to use a try here in order to catch the exception emitted by
        // the javaparser library instead of just returning an empty option.
        // In almost all cases we get here the exception is thrown. Check impact on performance
        // and hopefully find a better solution if necessary.
        val isInnerTypeDecl = Try(typeDecl.containerType().isPresent).getOrElse(false)
        if (isInnerTypeDecl) {
          nameOrFullName(typeDecl.containerType().get, fullyQualified).map(_ + "$" + typeName)
        } else {
          if (fullyQualified) {
            val packageName = typeDecl.getPackageName

            if (packageName == null || packageName == "") {
              Some(typeName)
            } else {
              Some(packageName + "." + typeName)
            }
          } else {
            Some(typeName)
          }
        }
    }
  }

  /** Add `typeName` to a global map and return it. The map is later passed to a pass that creates TYPE nodes for each
    * key in the map. Skip the `ANY` type, since this is created by default. TODO: I want the type registration not in
    * here but for now it is the easiest.
    */
  def registerType(typeName: String): String = {
    if (typeName != "ANY") {
      global.usedTypes.putIfAbsent(typeName, true)
    }
    typeName
  }
}

object TypeInfoCalculator {
  def isAutocastType(typeName: String): Boolean = {
    NumericTypes.contains(typeName)
  }

  object TypeConstants {
    val Byte: String     = "byte"
    val Short: String    = "short"
    val Int: String      = "int"
    val Long: String     = "long"
    val Float: String    = "float"
    val Double: String   = "double"
    val Char: String     = "char"
    val Boolean: String  = "boolean"
    val Object: String   = "java.lang.Object"
    val Class: String    = "java.lang.Class"
    val Iterator: String = "java.util.Iterator"
    val Void: String     = "void"
    val Any: String      = "ANY"
  }

  object TypeNameConstants {
    val Object: String = "Object"
  }

  // The method signatures for all methods implemented by java.lang.Object, as returned by JavaParser. This is used
  // to filter out Object methods when determining which functional interface method a lambda implements. See
  // https://docs.oracle.com/javase/8/docs/api/java/lang/FunctionalInterface.html for more details.
  val ObjectMethodSignatures: Set[String] = Set(
    "wait(long, int)",
    "equals(java.lang.Object)",
    "clone()",
    "toString()",
    "wait()",
    "hashCode()",
    "getClass()",
    "notify()",
    "finalize()",
    "wait(long)",
    "notifyAll()",
    "registerNatives()"
  )

  val NumericTypes: Set[String] = Set(
    "byte",
    "short",
    "int",
    "long",
    "float",
    "double",
    "char",
    "boolean",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.Character",
    "java.lang.Boolean"
  )

  def apply(global: Global, symbolResolver: SymbolResolver): TypeInfoCalculator = {
    new TypeInfoCalculator(global, symbolResolver)
  }
}
