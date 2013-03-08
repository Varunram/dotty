package dotty.tools.dotc
package core

import Periods._, Contexts._, Symbols._, Denotations._, Names._, Annotations._
import Types._, Flags._, Decorators._, Transformers._, StdNames._, Scopes._
import NameOps._
import Scopes.Scope
import collection.mutable
import collection.immutable.BitSet
import scala.reflect.io.AbstractFile
import Decorators.SymbolIteratorDecorator
import annotation.tailrec

trait SymDenotations {
  import SymDenotations._

  /** Factory method for SymDenotion creation. All creations
   *  should be done via this method.
   */
  def SymDenotation(
    symbol: Symbol,
    owner: Symbol,
    name: Name,
    initFlags: FlagSet,
    initInfo: Type,
    initPrivateWithin: Symbol = NoSymbol)(implicit ctx: Context): SymDenotation =
    if (symbol.isClass) new ClassDenotation(symbol, owner, name, initFlags, initInfo, initPrivateWithin)
    else new SymDenotation(symbol, owner, name, initFlags, initInfo, initPrivateWithin)

}
object SymDenotations {

  /** A sym-denotation represents the contents of a definition
   *  during a period.
   */
  class SymDenotation private[SymDenotations] (
    final val symbol: Symbol,
    _owner: Symbol,
    final val name: Name,
    initFlags: FlagSet,
    initInfo: Type,
    initPrivateWithin: Symbol = NoSymbol) extends SingleDenotation {

    // ------ Getting and setting fields -----------------------------

    private[this] var _flags: FlagSet = initFlags
    private[this] var _info: Type = initInfo
    private[this] var _privateWithin: Symbol = initPrivateWithin
    private[this] var _annotations: List[Annotation] = Nil

    /** The owner of the symbol */
    def owner: Symbol = _owner

    /** The flag set */
    final def flags: FlagSet = { ensureCompleted(); _flags }

    /** Update the flag set */
    private[core] final def flags_=(flags: FlagSet): Unit =
      _flags = flags

    /** Set given flags(s) of this denotation */
    final def setFlag(flags: FlagSet): Unit = { _flags |= flags }

    /** UnsSet given flags(s) of this denotation */
    final def resetFlag(flags: FlagSet): Unit = { _flags &~= flags }

    final def is(fs: FlagSet) = flags is fs
    final def is(fs: FlagSet, butNot: FlagSet) = flags is (fs, butNot)
    final def is(fs: FlagConjunction) = flags is fs
    final def is(fs: FlagConjunction, butNot: FlagSet) = flags is (fs, butNot)

    /** The type info.
     *  The info is an instance of TypeType iff this is a type denotation
     *  Uncompleted denotations set _info to a LazyType.
     */
    final def info: Type = _info match {
      case _info: LazyType => completedInfo(_info)
      case _ => _info
    }

    private def completedInfo(completer: LazyType): Type = {
      if (_flags is CompletionStarted) throw new CyclicReference(symbol)
      _flags |= CompletionStarted
      completer.complete(this)
      info
    }

    protected[core] final def info_=(tp: Type) =
      _info = tp

    /** The denotation is completed: all attributes are fully defined */
    final def isCompleted: Boolean = ! _info.isInstanceOf[LazyType]

    /** The completer of this denotation. @pre: Denotation is not yet completed */
    final def completer: LazyType = _info.asInstanceOf[LazyType]

    /** Make sure this denotation is completed */
    final def ensureCompleted(): Unit = info

    /** The privateWithin boundary, NoSymbol if no boundary is given.
     */
    final def privateWithin: Symbol = { ensureCompleted(); _privateWithin }

    /** Set privateWithin. */
    protected[core] final def privateWithin_=(sym: Symbol): Unit =
      _privateWithin = sym

    /** The annotations of this denotation */
    final def annotations: List[Annotation] = {
      ensureCompleted(); _annotations
    }

    /** Update the annotations of this denotation */
    private[core] final def annotations_=(annots: List[Annotation]): Unit = { _annotations = annots }

    /** Does this denotation have an annotation matching the given class symbol? */
    final def hasAnnotation(cls: Symbol)(implicit ctx: Context) = dropOtherAnnotations(annotations, cls).nonEmpty

    /** Add given annotation to the annotations of this denotation */
    final def addAnnotation(annot: Annotation): Unit = annotations =
      annot :: annotations

    @tailrec
    private def dropOtherAnnotations(anns: List[Annotation], cls: Symbol)(implicit ctx: Context): List[Annotation] = anns match {
      case ann :: rest => if (ann matches cls) anns else dropOtherAnnotations(rest, cls)
      case Nil => Nil
    }

    /** The symbols defined in this class when the class is not yet completed.
     *  @pre: this is a class
     */
    protected final def preCompleteDecls: Scope = _info match {
      case cinfo: LazyClassInfo => cinfo.decls
      case cinfo: ClassInfo => cinfo.decls
    }

    // ------ Names ----------------------------------------------

    /** The name with which the denoting symbol was created */
    final def originalName =
      if (flags is ExpandedName) initial.asSymDenotation.name else name

    /** The encoded full path name of this denotation, where outer names and inner names
     *  are separated by `separator` characters.
     *  Never translates expansions of operators back to operator symbol.
     *  Drops package objects. Represents terms in the owner chain by a simple `separator`.
     */
    def fullName(separator: Char)(implicit ctx: Context): Name =
      if (this == NoSymbol || owner == NoSymbol || owner.isEffectiveRoot) name
      else {
        var owner = this
        var sep = ""
        do {
          owner = owner.owner
          sep += separator
        } while (!owner.isClass)
        owner.skipPackageObject.fullName(separator) ++ sep ++ name
      }

    /** `fullName` where `.' is the separator character */
    def fullName(implicit ctx: Context): Name = fullName('.')

    // ----- Tests -------------------------------------------------

    /** Is this denotation a type? */
    override def isType: Boolean = name.isTypeName

    /** Is this denotation a class? */
    final def isClass: Boolean = symbol.isInstanceOf[ClassSymbol]

    /** Cast to class denotation */
    final def asClass: ClassDenotation = asInstanceOf[ClassDenotation]

    /** Special case tests for flags that are known a-priori and do not need loading
     *  flags.
     */
    final def isModule = _flags is Module
    final def isModuleVal = _flags is ModuleVal
    final def isModuleClass = _flags is ModuleClass
    final def isPackage = _flags is Package
    final def isPackageVal = _flags is PackageVal
    final def isPackageClass = _flags is PackageClass

    /** is this symbol the result of an erroneous definition? */
    def isError: Boolean = false

    /** Does this denotation refer to an existing definition?
     *  @return `false` if denotation is either `NoDenotation` or it
     *                  refers to a toplevel class or object that has no
     *                  definition in the source or classfile from which it is loaded.
     */
    override final def exists: Boolean = info ne NoType

    /** Make denotation not exist */
    final def markAbsent(): Unit =
      _info = NoType

    /** Is this symbol the root class or its companion object? */
    final def isRoot: Boolean = name.toTermName == nme.ROOT

    /** Is this symbol the empty package class or its companion object? */
    final def isEmptyPackage(implicit ctx: Context): Boolean =
      name.toTermName == nme.EMPTY_PACKAGE && owner.isRoot

    /** Is this symbol the empty package class or its companion object? */
    final def isEffectiveRoot(implicit ctx: Context) = isRoot || isEmptyPackage

    /** Is this symbol an anonymous class? */
    final def isAnonymousClass(implicit ctx: Context): Boolean =
      initial.asSymDenotation.name startsWith tpnme.ANON_CLASS

    /** Is this symbol an abstract type? */
    final def isAbstractType = isType && info.isRealTypeBounds

    /** Is this symbol an alias type? */
    final def isAliasType = isType && info.isAliasTypeBounds

    /** Is this symbol an abstract or alias type? */
    final def isAbstractOrAliasType = isType & info.isInstanceOf[TypeBounds]

    /** Is this definition contained in `boundary`?
     *  Same as `ownersIterator contains boundary` but more efficient.
     */
    final def isContainedIn(boundary: Symbol)(implicit ctx: Context): Boolean = {
      def recur(sym: Symbol): Boolean =
        if (sym eq boundary) true
        else if (sym eq NoSymbol) false
        else if (sym.isPackageClass && !boundary.isPackageClass) false
        else recur(sym.owner)
      recur(symbol)
    }

    /** Is this denotation static (i.e. with no outer instance)? */
    final def isStatic(implicit ctx: Context) = (this is Static) || owner.isStaticOwner

    /** Is this a package class or module class that defines static symbols? */
    final def isStaticOwner(implicit ctx: Context): Boolean =
      isPackageClass || isModuleClass && isStatic

    /** Is this denotation defined in the same scope and compilation unit as that symbol? */
    final def isCoDefinedWith(that: Symbol)(implicit ctx: Context) =
      (this.effectiveOwner == that.effectiveOwner) &&
      (  !this.effectiveOwner.isPackageClass
      || { val thisFile = this.symbol.associatedFile
           val thatFile = that.symbol.associatedFile
           (  thisFile == null
           || thatFile == null
           || thisFile.path == thatFile.path // Cheap possibly wrong check, then expensive normalization
           || thisFile.canonicalPath == thatFile.canonicalPath
           )
         }
      )

    /** Is this a denotation of a stable term (or an arbitrary type)? */
    final def isStable(implicit ctx: Context) = {
      val isUnstable =
        this.is(UnstableValue, butNot = Stable) ||
        info.isVolatile && !hasAnnotation(defn.uncheckedStableClass)
      !(isTerm && isUnstable)
    }

    /** Is this a user defined "def" method? Excluded are accessors and stable values */
    final def isSourceMethod = this is (Method, butNot = Accessor)

    /** Is this either not a method at all, or a parameterless method? */
    final def isParameterless(implicit ctx: Context) = info match {
      case _: MethodType | _: PolyType => false
      case _ => true
    }

    /** Is this a setter? */
    final def isGetter = (this is Accessor) && !originalName.isSetterName

    /** Is this a setter? */
    final def isSetter = (this is Accessor) && originalName.isSetterName

    /** is this the constructor of a class? */
    final def isClassConstructor = name == nme.CONSTRUCTOR

    /** Is this the constructor of a trait? */
    final def isTraitConstructor = name == nme.TRAIT_CONSTRUCTOR

    /** Is this the constructor of a trait or a class */
    final def isConstructor = name.isConstructorName

    /** Is this a local template dummmy? */
    final def isLocalDummy: Boolean = name.isLocalDummyName

    /** Does this symbol denote the primary constructor of its enclosing class? */
    final def isPrimaryConstructor(implicit ctx: Context) =
      isConstructor && owner.primaryConstructor == this

    /** Is this a subclass of the given class `base`? */
    def isSubClass(base: Symbol)(implicit ctx: Context) = false

    /** Is this a subclass of `base`,
     *  and is the denoting symbol also different from `Null` or `Nothing`?
     */
    def isNonBottomSubClass(base: Symbol)(implicit ctx: Context) = false

    /** Is this symbol a class that does not extend `AnyVal`? */
    final def isNonValueClass(implicit ctx: Context): Boolean =
      isClass && !isSubClass(defn.AnyValClass)

    /** Is this definition accessible as a member of tree with type `pre`?
     *  @param pre          The type of the tree from which the selection is made
     *  @param superAccess  Access is via super
     */
    final def isAccessibleFrom(pre: Type, superAccess: Boolean = false)(implicit ctx: Context): Boolean = {

      /** Are we inside definition of `boundary`? */
      def accessWithin(boundary: Symbol) =
        owner.isContainedIn(boundary) &&
          (!(this is JavaDefined) || // disregard package nesting for Java
           owner.enclosingPackage == boundary.enclosingPackage)

      /** Are we within definition of linked class of `boundary`? */
      def accessWithinLinked(boundary: Symbol) = {
        val linked = boundary.linkedClass
        (linked ne NoSymbol) && accessWithin(linked)
      }

      /** Is `pre` of the form C.this, where C is exactly the owner of this symbol,
       *  or, if this symbol is protected, a subclass of the owner?
       */
      def isCorrectThisType(pre: Type): Boolean = pre match {
        case ThisType(pclazz) =>
          (pclazz eq owner) ||
            (this is Protected) && pclazz.isNonBottomSubClass(owner)
        case _ => false
      }

      /** Is protected access to target symbol permitted? */
      def isProtectedAccessOK = {
        def fail(str: => String): Boolean = {
          ctx.diagnose(str)
          false
        }
        val cls = owner.enclosingSubClass
        if (!cls.exists)
          fail(
            s"""Access to protected $this not permitted because
               |enclosing ${ctx.owner.enclosingClass.showLocated} is not a subclass of
               |${owner.showLocated} where target is defined""".stripMargin)
        else if (
          !(  isType // allow accesses to types from arbitrary subclasses fixes #4737
           || pre.baseType(cls).exists
           || owner.isModuleClass // don't perform this check for static members
           ))
          fail(
            s"""Access to protected ${symbol.show} not permitted because
               |prefix type ${pre.widen.show} does not conform to
               |${cls.showLocated} where the access takes place""".stripMargin)
        else true
      }

      (pre == NoPrefix) || {
        val boundary = accessBoundary(owner)

        (  boundary.isTerm
        || boundary.isRoot
        || (accessWithin(boundary) || accessWithinLinked(boundary)) &&
             (  !(this is Local)
             || (owner is ImplClass) // allow private local accesses to impl class members
             || isCorrectThisType(pre)
             )
        || (this is Protected) &&
             (  superAccess
             || pre.isInstanceOf[ThisType]
             || ctx.phase.erasedTypes
             || isProtectedAccessOK
             )
        )
      }
    }

    //    def isOverridable: Boolean = !!! need to enforce that classes cannot be redefined
    //    def isSkolem: Boolean = ???

    // ------ access to related symbols ---------------------------------

    /** The class implementing this module, NoSymbol if not applicable. */
    final def moduleClass: Symbol = _info match {
      case info: TypeRefBySym if isModuleVal => info.fixedSym
      case info: LazyModuleInfo => info.mclass
      case _ => NoSymbol
    }

    /** The module implemented by this module class, NoSymbol if not applicable. */
    final def sourceModule: Symbol = _info match {
      case ClassInfo(_, _, _, _, selfType: TermRefBySym) if isModuleClass =>
        selfType.fixedSym
      case info: LazyModuleClassInfo =>
        info.modul
      case _ =>
        NoSymbol
    }

    /** The chain of owners of this denotation, starting with the denoting symbol itself */
    final def ownersIterator(implicit ctx: Context) = new Iterator[Symbol] {
      private[this] var current = symbol
      def hasNext = current.exists
      def next: Symbol = {
        val result = current
        current = current.owner
        result
      }
    }

    /** If this is a package object or its implementing class, its owner,
     *  otherwise the denoting symbol.
     */
    final def skipPackageObject(implicit ctx: Context): Symbol =
      if (this is PackageObject) owner else symbol

    /** The owner, skipping package objects. */
    final def effectiveOwner(implicit ctx: Context) = owner.skipPackageObject

    /** The class containing this denotation.
     *  If this denotation is already a class, return itself
     */
    final def enclosingClass(implicit ctx: Context): Symbol =
      if (isClass) symbol else owner.enclosingClass

    /** The top-level class containing this denotation,
     *  except for a toplevel module, where its module class is returned.
     */
    final def topLevelClass(implicit ctx: Context): Symbol = {
      val sym = topLevelSym
      if (sym.isClass) sym else sym.moduleClass
    }

    /** The top-level symbol containing this denotation. */
    final def topLevelSym(implicit ctx: Context): Symbol =
      if (owner.isPackageClass) symbol else owner.topLevelSym

    /** The package containing this denotation */
    final def enclosingPackage(implicit ctx: Context): Symbol =
      if (isPackageClass) symbol else owner.enclosingPackage

    /** The module object with the same (term-) name as this class or module class,
     *  and which is also defined in the same scope and compilation unit.
     *  NoSymbol if this module does not exist.
     */
    final def companionModule(implicit ctx: Context): Symbol = {
      owner.info.decl(name.toTermName)
        .suchThat(sym => sym.isModule && sym.isCoDefinedWith(symbol))
        .symbol
    }

    /** The class with the same (type-) name as this module or module class,
     *  and which is also defined in the same scope and compilation unit.
     *  NoSymbol if this class does not exist.
     */
    final def companionClass(implicit ctx: Context): Symbol =
      owner.info.decl(name.toTypeName)
        .suchThat(sym => sym.isClass && sym.isCoDefinedWith(symbol))
        .symbol

    /** If this is a class, the module class of its companion object.
     *  If this is a module class, its companion class.
     *  NoSymbol otherwise.
     */
    final def linkedClass(implicit ctx: Context): Symbol =
      if (this.isModuleClass) companionClass
      else if (this.isClass) companionModule.moduleClass
      else NoSymbol

    /** The class that encloses the owner of the current context
     *  and that is a subclass of this class. NoSymbol if no such class exists.
     */
    final def enclosingSubClass(implicit ctx: Context) =
      ctx.owner.ownersIterator.findSymbol(_.isSubClass(symbol))

    /** The non-private symbol whose name and type matches the type of this symbol
     *  in the given class.
     *  @param inClass   The class containing the symbol's definition
     *  @param site      The base type from which member types are computed
     */
    final def matchingSymbol(inClass: Symbol, site: Type)(implicit ctx: Context): Symbol = {
      var denot = inClass.info.nonPrivateDecl(name)
      if (denot.isTerm) {
        val targetType = site.memberInfo(symbol)
        if (denot.isOverloaded)
          denot = denot.atSignature(targetType.signature)
        if (!(site.memberInfo(denot.symbol) matches targetType))
          denot = NoDenotation
      }
      denot.symbol
    }

    /** The symbol, in class `inClass`, that is overridden by this denotation. */
    final def overriddenSymbol(inClass: ClassSymbol)(implicit ctx: Context): Symbol =
      if (owner isSubClass inClass) matchingSymbol(inClass, owner.thisType)
      else NoSymbol

    /** All symbols overriden by this denotation. */
    final def allOverriddenSymbols(implicit ctx: Context): Iterator[Symbol] =
      info.baseClasses.tail.iterator map overriddenSymbol filter (_.exists)

    /** The class or term symbol up to which this symbol is accessible,
     *  or RootClass if it is public.  As java protected statics are
     *  otherwise completely inaccessible in scala, they are treated
     *  as public.
     *  @param base  The access boundary to assume if this symbol is protected
     */
    final def accessBoundary(base: Symbol)(implicit ctx: Context): Symbol = {
      val fs = flags
      if (fs is PrivateOrLocal) owner
      else if (fs is StaticProtected) defn.RootClass
      else if (privateWithin.exists && !ctx.phase.erasedTypes) privateWithin
      else if (fs is Protected) base
      else defn.RootClass
    }

    /** The primary constructor of a class or trait, NoSymbol if not applicable. */
    def primaryConstructor(implicit ctx: Context): Symbol = NoSymbol

    // ----- type-related ------------------------------------------------

    /** The denotation as seen from prefix type */
    def asSeenFrom(pre: Type)(implicit ctx: Context): SingleDenotation =
      derivedSingleDenotation(symbol, info.asSeenFrom(pre, owner))

    /** The type parameters of a class symbol, Nil for all other symbols */
    def typeParams(implicit ctx: Context): List[TypeSymbol] = Nil

    /** The type This(cls), where cls is this class, NoPrefix for all other symbols */
    def thisType(implicit ctx: Context): Type = NoPrefix

    /** The named typeref representing the type constructor for this type.
     *  @throws ClassCastException is this is not a type
     */
    def typeConstructor(implicit ctx: Context): TypeRef =
      if (isPackageClass || owner.isTerm) symbolicRef
      else TypeRef(owner.thisType, name.asTypeName)

    /** The symbolic typeref representing the type constructor for this type.
     *  @throws ClassCastException is this is not a type
     */
    final def symbolicRef(implicit ctx: Context): TypeRef =
      TypeRef(owner.thisType, symbol.asType)

    /** The variance of this type parameter as an Int, with
     *  +1 = Covariant, -1 = Contravariant, 0 = Nonvariant, or not a type parameter
     */
    final def variance: Int =
      if (this is Covariant) 1
      else if (this is Contravariant) -1
      else 0

    override def toString = {
      val kindString =
        if (isModuleClass) "module class"
        else if (isClass) "class"
        else if (isType) "type"
        else if (isModule) "module"
        else "val"
      s"$kindString $name"
    }

    // ----- copies ------------------------------------------------------

    override protected def newLikeThis(s: Symbol, i: Type): SingleDenotation = new UniqueRefDenotation(s, i, validFor)

    /** Copy this denotation, overriding selective fields */
    final def copySymDenotation(
      symbol: Symbol = this.symbol,
      owner: Symbol = this.owner,
      name: Name = this.name,
      initFlags: FlagSet = this.flags,
      info: Type = this.info,
      privateWithin: Symbol = this.privateWithin,
      annotations: List[Annotation] = this.annotations)(implicit ctx: Context) =
    {
      val d = ctx.SymDenotation(symbol, owner, name, initFlags, info, privateWithin)
      d.annotations = annotations
      d
    }
  }

  /** The contents of a class definition during a period
   *  Note: important to leave initctx non-implicit, and to check that it is not
   *  retained after object construction.
   */
  class ClassDenotation private[SymDenotations] (
    symbol: Symbol,
    _owner: Symbol,
    name: Name,
    initFlags: FlagSet,
    initInfo: Type,
    initPrivateWithin: Symbol = NoSymbol)
    extends SymDenotation(symbol, _owner, name, initFlags, initInfo, initPrivateWithin) {

    import util.LRUCache

    // ----- denotation fields and accessors ------------------------------

    /** The symbol asserted to have type ClassSymbol */
    def classSymbol: ClassSymbol = symbol.asInstanceOf[ClassSymbol]

    /** The info asserted to have type ClassInfo */
    def classInfo(implicit ctx: Context): ClassInfo = super.info.asInstanceOf[ClassInfo]

    private[this] var _typeParams: List[TypeSymbol] = _

    /** The type parameters of this class */
    override final def typeParams(implicit ctx: Context): List[TypeSymbol] = {
      if (_typeParams == null) _typeParams == computeTypeParams
      _typeParams
    }

    private def computeTypeParams(implicit ctx: Context): List[TypeSymbol] =
      (preCompleteDecls.toList filter (_ is TypeParam)).asInstanceOf[List[TypeSymbol]]

    // ------ class-specific operations -----------------------------------

    private[this] var _thisType: Type = null

    override def thisType(implicit ctx: Context): Type = {
      if (_thisType == null) _thisType = computeThisType
      _thisType
    }

    private def computeThisType(implicit ctx: Context): Type = ThisType(classSymbol)
    /* was:
      if (isPackageClass && !isRoot)
        TermRef(owner.thisType, name.toTermName)
      else
        ThisType(classSymbol)
     */

    private[this] var _typeConstructor: TypeRef = null

    override def typeConstructor(implicit ctx: Context): TypeRef = {
      if (_typeConstructor == null) _typeConstructor = super.typeConstructor
      _typeConstructor
    }

    private[this] var _baseClasses: List[ClassSymbol] = null
    private[this] var _superClassBits: BitSet = null

    private def computeBases(implicit ctx: Context): Unit = {
      val seen = new mutable.BitSet
      val locked = new mutable.BitSet
      def addBaseClasses(bcs: List[ClassSymbol], to: List[ClassSymbol])
          : List[ClassSymbol] = bcs match {
        case bc :: bcs1 =>
          val id = bc.superId
          if (seen contains id) to
          else if (locked contains id) throw new CyclicReference(symbol)
          else {
            locked += id
            val bcs1added = addBaseClasses(bcs1, to)
            seen += id
            if (bcs1added eq bcs1) bcs else bc :: bcs1added
          }
        case nil =>
          to
      }
      def addParentBaseClasses(ps: List[Type], to: List[ClassSymbol]): List[ClassSymbol] = ps match {
        case p :: ps1 =>
          addBaseClasses(p.baseClasses, addParentBaseClasses(ps1, to))
        case nil =>
          to
      }
      _baseClasses = classSymbol :: addParentBaseClasses(classInfo.classParents, Nil)
      _superClassBits = ctx.uniqueBits.findEntryOrUpdate(seen.toImmutable)
    }

    /** A bitset that contains the superId's of all base classes */
    private def superClassBits(implicit ctx: Context): BitSet = {
      if (_superClassBits == null) computeBases
      _superClassBits
    }

    /** The base classes of this class in linearization order,
     *  with the class itself as first element.
     */
    def baseClasses(implicit ctx: Context): List[ClassSymbol] = {
      if (_baseClasses == null) computeBases
      _baseClasses
    }

    final override def isNonBottomSubClass(base: Symbol)(implicit ctx: Context): Boolean =
      base.isClass &&
      (  (symbol eq base)
      || (superClassBits contains base.superId)
      || (this is Erroneous)
      || (base is Erroneous)
      )

    final override def isSubClass(base: Symbol)(implicit ctx: Context) =
      isNonBottomSubClass(base) ||
        base.isClass && ((symbol eq defn.NothingClass) || (symbol eq defn.NullClass))

    private[this] var _memberFingerPrint: FingerPrint = FingerPrint.empty

    private def computeMemberFingerPrint(implicit ctx: Context): FingerPrint = {
      var fp = FingerPrint()
      var e = info.decls.lastEntry
      while (e != null) {
        fp.include(name)
        e = e.prev
      }
      var ps = classInfo.classParents
      while (ps.nonEmpty) {
        val parent = ps.head.typeSymbol
        parent.denot match {
          case classd: ClassDenotation =>
            fp.include(classd.memberFingerPrint)
            parent.denot.setFlag(Frozen)
          case _ =>
        }
        ps = ps.tail
      }
      fp
    }

    /** A bloom filter for the names of all members in this class.
     *  Makes sense only for parent classes, and should definitely
     *  not be used for package classes because cache never
     *  gets invalidated.
     */
    def memberFingerPrint(implicit ctx: Context): FingerPrint = {
      assert(classSymbol.hasChildren)
      if (_memberFingerPrint == FingerPrint.empty) _memberFingerPrint = computeMemberFingerPrint
      _memberFingerPrint
    }

    private[this] var _memberCache: LRUCache[Name, PreDenotation] = null

    private def memberCache: LRUCache[Name, PreDenotation] = {
      if (_memberCache == null) _memberCache = new LRUCache
      _memberCache
    }

    /** Enter a symbol in current scope.
     *  Note: We require that this does not happen after the first time
     *  someone does a findMember on a subclass.
     */
    def enter(sym: Symbol)(implicit ctx: Context) = {
      require(!(this is Frozen))
      info.decls.openForMutations.enter(sym)
      if (_memberFingerPrint != FingerPrint.empty)
        memberFingerPrint.include(sym.name)
      if (_memberCache != null)
        memberCache invalidate sym.name
    }

    /** Delete symbol from current scope.
     *  Note: We require that this does not happen after the first time
     *  someone does a findMember on a subclass.
     */
    def delete(sym: Symbol)(implicit ctx: Context) = {
      require(!(this is Frozen))
      info.decls.openForMutations.unlink(sym)
      if (_memberFingerPrint != FingerPrint.empty)
        computeMemberFingerPrint
      if (_memberCache != null)
        memberCache invalidate sym.name
    }

    /** All members of this class that have the given name.
     *  The elements of the returned pre-denotation all
     *  have existing symbols.
     */
    final def membersNamed(name: Name)(implicit ctx: Context): PreDenotation = {
      var denots: PreDenotation = memberCache lookup name
      if (denots == null) {
        if (!classSymbol.hasChildren || (memberFingerPrint contains name)) {
          val ownDenots = info.decls.denotsNamed(name)
          denots = ownDenots
          var ps = classInfo.classParents
          while (ps.nonEmpty) {
            val parentSym = ps.head.symbol
            parentSym.denot match {
              case parentd: ClassDenotation =>
                denots = denots union
                  parentd.membersNamed(name)
                    .filterAsSeenFrom(thisType, Flags.Private)
                    .filterDisjoint(ownDenots)
              case _ =>
            }
          }
        } else {
          denots = NoDenotation
        }
        memberCache enter (name, denots)
      }
      denots
    }

    private[this] var baseTypeCache: java.util.HashMap[CachedType, Type] = null
    private[this] var baseTypeValid: RunId = NoRunId

    final def baseTypeOf(tp: Type)(implicit ctx: Context): Type = {

      def computeBaseTypeOf(tp: Type): Type = tp match {
        case tp: TypeProxy =>
          baseTypeOf(tp.underlying)
        case AndType(tp1, tp2) =>
          baseTypeOf(tp1) & baseTypeOf(tp2)
        case OrType(tp1, tp2) =>
          baseTypeOf(tp1) | baseTypeOf(tp2)
        case tp: ClassInfo =>
          def foldGlb(bt: Type, ps: List[Type]): Type = ps match {
            case p :: ps1 => foldGlb(bt & baseTypeOf(p), ps1)
            case _ => bt
          }
          if (tp.cls eq symbol)
            tp.typeConstructor
          else if (tp.cls.classDenot.superClassBits contains symbol.superId)
            tp.rebase(foldGlb(NoType, tp.classParents))
          else
            NoType
        case _ =>
          NoType
      }

      if (symbol.isStatic) symbol.typeConstructor
      else tp match {
        case tp: CachedType =>
          if (baseTypeValid != ctx.runId) {
            baseTypeCache = new java.util.HashMap[CachedType, Type]
            baseTypeValid = ctx.runId
          }
          var basetp = baseTypeCache get tp
          if (basetp == null) {
            baseTypeCache.put(tp, NoType)
            basetp = computeBaseTypeOf(tp)
            baseTypeCache.put(tp, basetp)
          } else if (basetp == NoType) {
            throw new CyclicReference(symbol)
          }
          basetp
        case _ =>
          computeBaseTypeOf(tp)
      }
    }

    private[this] var memberNamesCache: Map[NameFilter, Set[Name]] = Map()

    def memberNames(keepOnly: NameFilter)(implicit ctx: Context): Set[Name] = {
      def computeMemberNames: Set[Name] = {
        val inheritedNames = (classInfo.classParents flatMap (_.memberNames(keepOnly, thisType))).toSet
        val ownNames = info.decls.iterator map (_.name)
        val candidates = inheritedNames ++ ownNames
        candidates filter (keepOnly(thisType, _))
      }
      if (isPackageClass) computeMemberNames // don't cache package member names; they might change
      else memberNamesCache get keepOnly match {
        case Some(names) =>
          names
        case _ =>
          setFlag(Frozen)
          val names = computeMemberNames
          memberNamesCache = memberNamesCache.updated(keepOnly, names)
          names
      }
    }

    private[this] var fullNameCache: Map[Char, Name] = Map()

    override final def fullName(separator: Char)(implicit ctx: Context): Name =
      fullNameCache get separator match {
        case Some(fn) =>
          fn
        case _ =>
          val fn = super.fullName(separator)
          fullNameCache = fullNameCache.updated(separator, fn)
          fn
      }

    // to avoid overloading ambiguities
    override def fullName(implicit ctx: Context): Name = super.fullName

    override def primaryConstructor(implicit ctx: Context): Symbol = {
      val cname =
        if (this is Trait | ImplClass) nme.TRAIT_CONSTRUCTOR else nme.CONSTRUCTOR
      info.decls.denotsNamed(cname).first.symbol
    }
  }

  object NoDenotation extends SymDenotation(
    NoSymbol, NoSymbol, "<none>".toTermName, EmptyFlags, NoType) {
    override def isTerm = false
    override def isType = false
    override def owner: Symbol = throw new AssertionError("NoDenotation.owner")
  }

  // ---- Completion --------------------------------------------------------

  /** Instances of LazyType are carried by uncompleted symbols.
   *  Note: LazyTypes double up as (constant) functions from Symbol and
   *  from (TermSymbol, ClassSymbol) to LazyType. That way lazy types can be
   *  directly passed to symbol creation methods in Symbols that demand instances
   *  of these types.
   */
  abstract class LazyType extends UncachedGroundType
    with (Symbol => LazyType)
    with ((TermSymbol, ClassSymbol) => LazyType) {

    /** Sets all missing fields of given denotation */
    def complete(denot: SymDenotation): Unit

    def apply(sym: Symbol) = this
    def apply(module: TermSymbol, modcls: ClassSymbol) = this
  }

  /** A lazy type for classes that contains an initial pre-complete scope.
   *  Typically this is for type parameters
   */
  abstract class LazyClassInfo(val decls: Scope) extends LazyType

  /** A lazy type for module classes that points back to the source module.
   *  Needed so that `sourceModule` works before completion.
   */
  abstract class LazyModuleClassInfo(val modul: TermSymbol) extends LazyClassInfo(newScope)

  /** A lazy type for modules that points to the module class.
   *  Needed so that `moduleClass` works before completion.
   *  Completion of modules is always completion of the underlying
   *  module class, followed by copying the relevant fields to the module.
   */
  class LazyModuleInfo(val mclass: ClassSymbol)(implicit cctx: CondensedContext) extends LazyType {
    def complete(denot: SymDenotation): Unit = {
      val from = denot.moduleClass.denot.asClass
      denot.setFlag(from.flags.toTermFlags & RetainedModuleValFlags)
      denot.annotations = from.annotations filter (_.appliesToModule)
        // !!! ^^^ needs to be revised later. The problem is that annotations might
        // only apply to the module but not to the module class. The right solution
        // is to have the module class completer set the annotations of both the
        // class and the module.
      denot.info = mclass.symbolicRef
      denot.privateWithin = from.privateWithin
    }
  }

  /** A completer for missing references */
  class StubInfo()(implicit cctx: CondensedContext) extends LazyType {

    def initializeToDefaults(denot: SymDenotation) = {
      denot.info = denot match {
        case denot: ClassDenotation =>
          ClassInfo(denot.owner.thisType, denot.classSymbol, Nil, EmptyScope)
        case _ =>
          ErrorType
      }
      denot.privateWithin = NoSymbol
    }

    def complete(denot: SymDenotation): Unit = {
      val sym = denot.symbol
      val file = sym.associatedFile
      val (location, src) =
        if (file != null) (s" in $file", file.toString)
        else ("", "the signature")
      cctx.error(
        s"""|bad symbolic reference. A signature$location refers to ${cctx.fresh.withDebug.show(denot.name)}
            |in ${denot.owner.showKind} ${denot.owner.showFullName} which is not available.
            |It may be completely missing from the current classpath, or the version on
            |the classpath might be incompatible with the version used when compiling $src.""".stripMargin)
      if (cctx.debug) (new Throwable).printStackTrace
      initializeToDefaults(denot)
    }
  }

  // ---- Fingerprints -----------------------------------------------------

  /** A fingerprint is a bitset that acts as a bloom filter for sets
   *  of names.
   */
  class FingerPrint(val bits: Array[Long]) extends AnyVal {
    import FingerPrint._

    /** Include some bits of name's hashcode in set */
    def include(name: Name): Unit = {
      val hash = name.hashCode & Mask
      bits(hash >> WordSizeLog) |= (1L << hash)
    }

    /** Include all bits of `that` fingerprint in set */
    def include(that: FingerPrint): Unit =
      for (i <- 0 until NumWords) bits(i) |= that.bits(i)

    /** Does set contain hash bits of given name? */
    def contains(name: Name): Boolean = {
      val hash = name.hashCode & Mask
      (bits(hash >> WordSizeLog) & (1L << hash)) != 0
    }
  }

  object FingerPrint {
    def apply() = new FingerPrint(new Array[Long](NumWords))
    val empty = new FingerPrint(null)
    private final val WordSizeLog = 6
    private final val NumWords = 32
    private final val NumBits = NumWords << WordSizeLog
    private final val Mask = NumBits - 1
  }
}
