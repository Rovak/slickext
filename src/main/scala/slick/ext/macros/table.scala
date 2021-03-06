package slickext.macros

import scala.reflect.macros.blackbox.Context
import scala.reflect._
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation

class table[T](tableName: String = "") extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro TableMacroImpl.impl
}

class TableMacroImpl(val c: Context) {

  import c.universe._

  case class TableInfo(
    productType: Type,
    productCompanionType: Type,
    productFields: Iterable[MethodSymbol],
    annotationParams: Iterable[Tree]
  )

  def impl(annottees: c.Expr[Any]*): c.Expr[Any] = annottees.map(_.tree) match {
    case (classDecl: ClassDef) :: Nil =>
      extractTableInfo() match {
        case Right(info) =>
          c.Expr(genCode(classDecl, info))
        case Left(err) =>
          c.abort(c.enclosingPosition, err)
      }
    case decl =>
      c.abort(c.enclosingPosition, "Underlying class must not be top-level and without companion")
  }

  private def genCode(classDef: ClassDef, info: TableInfo) = {
    val q"$mods class $tpname[..$tparams] $ctorMods(...$paramss) extends { ..$earlydefns } with ..$parents { $self => ..$stats }" = classDef
    val tableName = Literal(Constant(getTableName(info)))
    val definedColumns = getDefinedColumns(stats)
    val notDefinedFields = info.productFields.filterNot {
      case f => definedColumns.contains(f.name.decodedName.toString)
    }
    val generatedCols = genColumns(info, notDefinedFields)
    val allColNames =  info.productFields.map(_.name)
    val mapping = if(allColNames.size <= 22) genSimpleMapping(info, allColNames) else genHListMapping(info, allColNames)
    q"""
      $mods class $tpname(tag: Tag, slickextTbName: String) extends Table[${info.productType}](tag, slickextTbName) {
        ..$stats
        ..$generatedCols
        $mapping
      }
      object ${tpname.toTermName} extends TableQuery[${tpname}]({tag: Tag => new $tpname(tag, $tableName)})
      """

  }

  private def genSimpleMapping(info: TableInfo, columns: Iterable[TermName]) = {
    val pCompType = info.productCompanionType.typeSymbol.name.toTermName
    q"def * = (..$columns) <> ((${pCompType}.apply _).tupled, ${pCompType}.unapply)"
  }

  private def genHListMapping(info: TableInfo, columns: Iterable[TermName]) = {
    val hlist = hlistConcat(columns)
    val pCompType = info.productCompanionType
    val pType = info.productType
    val columnElems = (0 until columns.size).map(i => q"x($i)")
    val productHList = hlistConcat(columns.map(n =>q"x.$n"))
    val toProduct = q"{case x => new $pType(..$columnElems)}"
    val fromProduct = q"{x: $pType => Option($productHList)}"
    q"def * = ($hlist).shaped <> ($toProduct, $fromProduct)"
  }

  private def genColumns(info: TableInfo, notDefined: Iterable[MethodSymbol]) = {
    notDefined.map{ f =>
      val fName = f.name
      val fType = f.returnType
      val columnName = Literal(Constant(snakify(fName.decodedName.toString)))
      if(fName.decodedName.toString == "id" &&
        (fType =:= typeOf[Option[Long]] || fType =:= typeOf[Option[Int]] )) {
        q"""
         def ${fName.toTermName} = column[$fType]($columnName, O.PrimaryKey, O.AutoInc)
        """
      } else {
        q"""
         def ${fName.toTermName} = column[$fType]($columnName)
        """
      }
    }
  }

  private def extractTableInfo() = {
    c.macroApplication match {
      case  q"new $annotationTpe[$paramTypeTree]().$method(..$methodParams)" =>
        val pType = typeOfTree(paramTypeTree)
        val pCompType = pType.companion
        val pFields = getProductFields(pType)
        Right(TableInfo(pType, pCompType, pFields, Nil))
      case  q"new $annotationTpe[$paramTypeTree](..$params).$method(..$methodParams)" =>
        val pType = typeOfTree(paramTypeTree)
        val pCompType = pType.companion
        val pFields = getProductFields(pType)
        Right(TableInfo(pType, pCompType, pFields, params))
      case _ =>
        Left(c.macroApplication.toString + " illegal macro application, correct usage @table[SomeType]")
    }
  }

  private def getProductFields(productTpe: Type) = {
    productTpe.decls.collect {
      case m: MethodSymbol if m.isCaseAccessor => m
    }
  }

  private def getDefinedColumns(defs: Seq[Tree]) = {
    defs.collect {
      case m: DefDef =>
        val q" def $name = $expr" = m
        name.decodedName.toString
    }
  }

  private def getTableName(info: TableInfo): String = {
    def decodeAnnotateParam(param: Tree) = {
      val q"$fname = $fv" = param
      fname.toString -> fv
    }
    info.annotationParams.map(decodeAnnotateParam(_)).collectFirst {
      case ("tableName", Literal(Constant(name: String))) if name != "" => name
    }.getOrElse(snakify(info.productType.typeSymbol.name.decodedName.toString))
  }

  private def hlistConcat[T: Liftable ](elems: Iterable[T]) = {
    val HNil = q"slick.collection.heterogeneous.HNil": Tree
    elems.toList.reverse.foldLeft(HNil) { (list, c) =>
      q"$c :: $list"
    }
  }

  private def typeOfTree(tree: Tree) = {
    c.typecheck(q"??? : $tree").tpe
  }

  private def snakify(name: String) = {
    "(?<!^)([A-Z\\d])".r.replaceAllIn(name, "_$1").toLowerCase()
  }
}
