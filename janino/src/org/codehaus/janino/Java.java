
/*
 * Janino - An embedded Java[TM] compiler
 *
 * Copyright (c) 2001-2010, Arno Unkrig
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *       following disclaimer.
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *       following disclaimer in the documentation and/or other materials provided with the distribution.
 *    3. The name of the author may not be used to endorse or promote products derived from this software without
 *       specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.codehaus.janino;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.commons.compiler.Location;
import org.codehaus.commons.nullanalysis.Nullable;
import org.codehaus.janino.CodeContext.Offset;
import org.codehaus.janino.IClass.IMethod;
import org.codehaus.janino.Java.FunctionDeclarator.FormalParameter;
import org.codehaus.janino.Java.FunctionDeclarator.FormalParameters;
import org.codehaus.janino.Visitor.ElementValueVisitor;
import org.codehaus.janino.Visitor.TypeArgumentVisitor;
import org.codehaus.janino.util.Traverser;
import org.codehaus.janino.util.iterator.ReverseListIterator;

/**
 * This wrapper class defines classes that represent the elements of the
 * Java&trade; programming language.
 * <p>
 * Notice:
 * <p>
 * 'JLS7' refers to the <a href="http://docs.oracle.com/javase/specs/">Java Language Specification, Java SE 7
 * Edition</a>.
 */
@SuppressWarnings({ "rawtypes", "unchecked" }) public final
class Java {

    private Java() {} // Don't instantiate me.

    /** Representation of a Java&trade; 'scope', e.g. a compilation unit, type, method or block. */
    public
    interface Scope {

        /** @return The scope that encloses this scope, or {@code null} */
        Scope getEnclosingScope();
    }

    /** This interface is implemented by objects which are associated with a location in the source code. */
    public
    interface Locatable {

        /** @return The location of this object */
        Location getLocation();

        /**
         * Throw a {@link CompileException} with the given message and this
         * object's location.
         *
         * @param message The message to report
         */
        void throwCompileException(String message) throws CompileException;
    }

    /** Abstract implementation of {@link Locatable}. */
    public abstract static
    class Located implements Locatable {

        /** Indication of 'no' or 'unknown' location. */
        public static final Located NOWHERE = new Located(Location.NOWHERE) {};

        private final Location location;

        protected
        Located(Location location) {
            //assert location != null;
            this.location = location;
        }

        // Implement "Locatable".

        @Override public Location
        getLocation() { return this.location; }

        @Override public void
        throwCompileException(String message) throws CompileException {
            throw new CompileException(message, this.location);
        }
    }

    /** Holds the result of {@link Parser#parseCompilationUnit}. */
    public static final
    class CompilationUnit implements Scope {

        /** A string that explains the 'file' (or similar resource) where this CU was loaded from. */
        @Nullable public final String optionalFileName;

        /** The package declaration at the very top of this CU (if any). */
        @Nullable public PackageDeclaration optionalPackageDeclaration;

        /** The IMPORT declarations in this CU. */
        public final List<ImportDeclaration> importDeclarations = new ArrayList();

        /** The top-level declarations in this CU. */
        public final List<PackageMemberTypeDeclaration> packageMemberTypeDeclarations = new ArrayList();

        public
        CompilationUnit(@Nullable String optionalFileName) { this.optionalFileName = optionalFileName; }

        // Implement "Scope".

        @Override public Scope
        getEnclosingScope() { throw new JaninoRuntimeException("A compilation unit has no enclosing scope"); }

        /** Sets the package declaration of this CU. */
        public void
        setPackageDeclaration(PackageDeclaration packageDeclaration) {
            this.optionalPackageDeclaration = packageDeclaration;
        }

        /** Adds one IMPORT declaration to this CU. */
        public void
        addImportDeclaration(CompilationUnit.ImportDeclaration id) {

            // Conflicting imports are checked in UnitCompiler, not here.
            this.importDeclarations.add(id);
        }

        /** Adds one top-level type declaration to this CU. */
        public void
        addPackageMemberTypeDeclaration(PackageMemberTypeDeclaration pmtd) {
            this.packageMemberTypeDeclarations.add(pmtd);
            pmtd.setDeclaringCompilationUnit(this);
        }

        /** Gets all classes and interfaces declared in this compilation unit. */
        public PackageMemberTypeDeclaration[]
        getPackageMemberTypeDeclarations() {
            return (PackageMemberTypeDeclaration[]) this.packageMemberTypeDeclarations.toArray(
                new PackageMemberTypeDeclaration[this.packageMemberTypeDeclarations.size()]
            );
        }

        /**
         * Return the package member class or interface declared with the given name.
         * @param name Declared (i.e. not the fully qualified) name
         * @return <code>null</code> if a package member type with that name is not declared in this compilation unit
         */
        @Nullable public PackageMemberTypeDeclaration
        getPackageMemberTypeDeclaration(String name) {
            for (PackageMemberTypeDeclaration pmtd : this.packageMemberTypeDeclarations) {
                if (pmtd.getName().equals(name)) return pmtd;
            }
            return null;
        }

        /** Represents a 'single-type import declaration' like '{@code import java.util.Map;}'. */
        public static
        class SingleTypeImportDeclaration extends ImportDeclaration {

            /** The identifiers that constitute the type to be imported, e.g. 'java', 'util', 'Map'. */
            public final String[] identifiers;

            public
            SingleTypeImportDeclaration(Location location, String[] identifiers) {
                super(location);
                this.identifiers = identifiers;
            }

            @Override @Nullable public final <R, EX extends Throwable> R
            accept(Visitor.ImportVisitor<R, EX> visitor) throws EX {
                return visitor.visitSingleTypeImportDeclaration(this);
            }

            @Override public String
            toString() { return "import " + Java.join(this.identifiers, ".") + ';'; }
        }

        /** Represents a type-import-on-demand declaration like {@code  import java.util.*;}. */
        public static
        class TypeImportOnDemandDeclaration extends ImportDeclaration {

            /** The identifiers that constitute the package or type to import from, e.g. 'java', 'util'. */
            public final String[] identifiers;

            public
            TypeImportOnDemandDeclaration(Location location, String[] identifiers) {
                super(location);
                this.identifiers = identifiers;
            }

            @Override @Nullable public final <R, EX extends Throwable> R
            accept(Visitor.ImportVisitor<R, EX> visitor) throws EX {
                return visitor.visitTypeImportOnDemandDeclaration(this);
            }

            @Override public String
            toString() { return "import " + Java.join(this.identifiers, ".") + ".*;"; }
        }

        /**
         * Represents a single static import declaration like<pre>
         *     import java.util.Collections.EMPTY_MAP;</pre>
         */
        public static
        class SingleStaticImportDeclaration extends ImportDeclaration {

            /**
             * The identifiers that constitute the member to be imported, e.g. 'java', 'util', 'Collections',
             * 'EMPTY_MAP'.
             */
            public final String[] identifiers;

            public
            SingleStaticImportDeclaration(Location location, String[] identifiers) {
                super(location);
                this.identifiers = identifiers;
            }

            @Override @Nullable public final <R, EX extends Throwable> R
            accept(Visitor.ImportVisitor<R, EX> visitor) throws EX {
                return visitor.visitSingleStaticImportDeclaration(this);
            }
        }

        /**
         * Represents a static-import-on-demand declaration like<pre>
         *     import java.util.Collections.*;</pre>
         */
        public static
        class StaticImportOnDemandDeclaration extends ImportDeclaration {

            /** The identifiers that constitute the type to import from, e.g. 'java', 'util', 'Collections'. */
            public final String[] identifiers;

            public
            StaticImportOnDemandDeclaration(Location location, String[] identifiers) {
                super(location);
                this.identifiers = identifiers;
            }

            @Override @Nullable public final <R, EX extends Throwable> R
            accept(Visitor.ImportVisitor<R, EX> visitor) throws EX {
                return visitor.visitStaticImportOnDemandDeclaration(this);
            }
        }

        /** Base class for the various IMPORT declarations. */
        public abstract static
        class ImportDeclaration extends Java.Located {

            public
            ImportDeclaration(Location location) { super(location); }

            /**
             * Invokes the '{@code visit...()}' method of {@link Visitor.ImportVisitor} for the concrete {@link
             * ImportDeclaration} type.
             */
            @Nullable public abstract <R, EX extends Throwable> R
            accept(Visitor.ImportVisitor<R, EX> visitor) throws EX;
        }
    }

    /** Representation of a Java &trade; annotation. */
    public
    interface Annotation extends Locatable, ElementValue {

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.AnnotationVisitor} for the concrete {@link
         * Annotation} type.
         */
        @Nullable <R, EX extends Throwable> R
        accept(Visitor.AnnotationVisitor<R, EX> visitor) throws EX;

        /**
         * Sets the enclosing scope for this annotation.
         */
        @Override
        void
        setEnclosingScope(Scope enclosingScope);

        /**
         * @return The type of this annotation
         */
        Type getType();
    }

    /** Convenience class. */
    public abstract static
    class AbstractAnnotation implements Annotation {

        /** The type of this annotation. */
        public final Type type;

        public
        AbstractAnnotation(Type type) { this.type = type; }

        @Override public void
        setEnclosingScope(Scope enclosingScope) { this.type.setEnclosingScope(enclosingScope); }

        @Override public Location
        getLocation() { return this.type.getLocation(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(ElementValueVisitor<R, EX> visitor) throws EX { return visitor.visitAnnotation(this); }

        @Override public void
        throwCompileException(String message) throws CompileException {
            throw new CompileException(message, this.getLocation());
        }
    }

    /** Representation of a 'marker annotation', i.e. an annotation without any elements in parentheses. */
    public static final
    class MarkerAnnotation extends AbstractAnnotation {

        public
        MarkerAnnotation(Type type) { super(type); }

        @Override public String toString() { return "@" + this.type; }

        @Override public Type
        getType() { return this.type; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.AnnotationVisitor<R, EX> visitor) throws EX { return visitor.visitMarkerAnnotation(this); }
    }

    /**
     * Representation of a 'single-element annotation', i.e. an annotation followed by a single element in parentheses.
     */
    public static final
    class SingleElementAnnotation extends AbstractAnnotation {

        /** The element value associated with this single-element annotation. */
        public final ElementValue elementValue;

        public
        SingleElementAnnotation(ReferenceType type, ElementValue elementValue) {
            super(type);
            this.elementValue = elementValue;
        }

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            super.setEnclosingScope(enclosingScope);
            this.elementValue.setEnclosingScope(enclosingScope);
        }

        @Override public String toString() { return "@" + this.type + '(' + this.elementValue + ')'; }

        @Override public Type
        getType() { return this.type; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.AnnotationVisitor<R, EX> visitor)
        throws EX { return visitor.visitSingleElementAnnotation(this); }
    }

    /** A 'normal annotation', i.e. an annotation with multiple elements in parentheses and curly braces. */
    public static final
    class NormalAnnotation extends AbstractAnnotation {

        /** The element-value-pairs associated with this annotation. */
        public final ElementValuePair[] elementValuePairs;

        public
        NormalAnnotation(ReferenceType type, ElementValuePair[] elementValuePairs) {
            super(type);
            this.elementValuePairs = elementValuePairs;
        }

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            super.setEnclosingScope(enclosingScope);
            for (ElementValuePair elementValuePair : this.elementValuePairs) {
                elementValuePair.elementValue.setEnclosingScope(enclosingScope);
            }
        }

        @Override public Type
        getType() { return this.type; }

        @Override public String
        toString() {
            switch (this.elementValuePairs.length) {
            case 0:  return "@" + this.type + "()";
            case 1:  return "@" + this.type + "(" + this.elementValuePairs[0] + ")";
            default: return "@" + this.type + "(" + this.elementValuePairs[0] + ", ...)";
            }
        }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.AnnotationVisitor<R, EX> visitor) throws EX   { return visitor.visitNormalAnnotation(this); }
    }

    /** Representation of the modifier flags and annotations that are associated with a declaration. */
    public static
    class Modifiers {

        /** The or'ed constants declared in {@link Mod}. */
        public final short accessFlags;

        /** The annotations. */
        public final Annotation[] annotations;

        /** A 'blank' {@link Modifiers} object: No flags, no annotations. */
        public
        Modifiers() {
            this.accessFlags = Mod.NONE;
            this.annotations = new Annotation[0];
        }

        /** A 'simple' {@link Modifiers} object: Flags, but no annotations. */
        public
        Modifiers(short modifiers) {
            this.accessFlags = modifiers;
            this.annotations = new Annotation[0];
        }

        public
        Modifiers(short modifiers, Annotation[] annotations) {
            this.accessFlags = modifiers;
            this.annotations = annotations;
        }

        /** Sets the enclosing scope of the annotations. */
        public void
        setEnclosingScope(Scope enclosingScope) {
            for (Annotation a : this.annotations) a.setEnclosingScope(enclosingScope);
        }

        /** @return This object, with the given {@code modifiersToAdd} added. */
        public Modifiers
        add(int modifiersToAdd) {
            return new Modifiers((short) (this.accessFlags | modifiersToAdd), this.annotations);
        }

        /** @return This object, with the given {@code modifiersToRemove} removed. */
        public Modifiers
        remove(int modifiersToRemove) {
            return new Modifiers((short) (this.accessFlags & ~modifiersToRemove), this.annotations);
        }

        /**
         * @param newAccess One of {@link Mod#PUBLIC}, {@link Mod#PRIVATE}, {@link Mod#PROTECTED}, {@link Mod#PACKAGE}
         * @return          This object, with the access changed to {@code newAccess}
         */
        public Modifiers
        changeAccess(int newAccess) {
            return new Modifiers((short) (this.accessFlags & ~Mod.PPP | newAccess), this.annotations);
        }

        public boolean
        isBlank() { return this.accessFlags == 0 && this.annotations.length == 0; }
    }

    /** Representation of a 'name = value' element in a {@link NormalAnnotation}. */
    public static
    class ElementValuePair {

        /** The element name. */
        public final String identifier;

        /** The element value. */
        public final ElementValue elementValue;

        public
        ElementValuePair(String identifier, ElementValue elementValue) {
            this.identifier   = identifier;
            this.elementValue = elementValue;
        }

        @Override public String
        toString() { return this.identifier + " = " + this.elementValue; }
    }

    /** Base of the possible element values in a {@link NormalAnnotation}. */
    public
    interface ElementValue extends Locatable {

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.ElementValueVisitor} for the concrete {@link
         * ElementValue} type.
         */
        @Nullable <R, EX extends Throwable> R
        accept(Visitor.ElementValueVisitor<R, EX> visitor) throws EX;

        /**
         * In most cases, the scope is the enclosing {@link BlockStatement}, except for top-level class/interface
         * annotation class-literal element-value-pairs, where the enclosing scope is the compilation unit.
         */
        void
        setEnclosingScope(Scope scope);
    }

    /**
     * An element value in the form of an array initializer, e.g. '<code>SuppressWarnings({ "null", "unchecked"
     * })</code>'.
     */
    public static
    class ElementValueArrayInitializer extends Located implements ElementValue {

        /** The element values in the body of the array initializer. */
        public final ElementValue[] elementValues;

        public
        ElementValueArrayInitializer(ElementValue[] elementValues, Location location) {
            super(location);
            this.elementValues = elementValues;
        }

        @Override public void
        setEnclosingScope(Scope scope) {
            for (ElementValue elementValue : this.elementValues) elementValue.setEnclosingScope(scope);
        }

        @Override public String
        toString() {
            switch (this.elementValues.length) {
            case 0:  return "{}";
            case 1:  return "{ " + this.elementValues[0] + " }";
            default: return "{ " + this.elementValues[0] + ", ... }";
            }
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.ElementValueVisitor<R, EX> visitor) throws EX {
            return visitor.visitElementValueArrayInitializer(this);
        }
    }

    /** Representation of a package declaration like {@code package com.acme.tools;}. */
    public static
    class PackageDeclaration extends Located {

        /** The package name, e.g. '{@code com.acme.tools}'. */
        public final String packageName;

        public
        PackageDeclaration(Location location, String packageName) {
            super(location);
            this.packageName = packageName;
        }
    }

    /** Base for the various kinds of type declarations, e.g. top-level class, member interface, local class. */
    public
    interface TypeDeclaration extends Annotatable, Locatable, Scope {

        /** @return The or'ed modifier flags of the type, as defined in {@link Mod} */
        short getModifierFlags();

        /**
         * Return the member type with the given name.
         * @return <code>null</code> if a member type with that name is not declared
         */
        @Nullable MemberTypeDeclaration getMemberTypeDeclaration(String name);

        /** @return The (possibly empty) set of member types declared inside this {@link TypeDeclaration} */
        Collection<MemberTypeDeclaration> getMemberTypeDeclarations();

        /**
         * Return the first method declared with the given name. (Does not honor inherited methods.)
         *
         * @return <code>null</code> if a method with this name is not declared
         */
        @Nullable MethodDeclarator getMethodDeclaration(String name);

        /**
         * @return The list of methods declared in this {@link TypeDeclaration}, not including methods declared in
         *         supertypes
         */
        List<MethodDeclarator> getMethodDeclarations();

        /** Determines the effective class name, e.g. "pkg.Outer$Inner". */
        String getClassName();

        /** Creates a unique name for a local class or interface. */
        String createLocalTypeName(String localTypeName);

        /** Creates a unique name for an anonymous class. */
        String createAnonymousClassName();

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.TypeDeclarationVisitor} for the concrete {@link
         * TypeDeclaration} type.
         */
        @Nullable <R, EX extends Throwable> R accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX;
    }

    /**
     * Representation of a Java&trade; element that can be annotated with a DOC comment ('<code>&#47;** ...
     * *&#47;</code>').
     */
    public
    interface DocCommentable {

        /** @return The doc comment of the object or {@code null} */
        @Nullable String getDocComment();

        /**
         * Returns <code>true</code> if the object has a doc comment and
         * the <code>&#64;deprecated</code> tag appears in the doc
         * comment.
         */
        boolean hasDeprecatedDocTag();
    }

    /**
     * Represents a class or interface declaration on compilation unit level. These are called "package member types"
     * because they are immediate members of a package.
     */
    public
    interface PackageMemberTypeDeclaration extends NamedTypeDeclaration {

        /** Sets the {@link CompilationUnit} in which this top-level type is declared. */
        void setDeclaringCompilationUnit(CompilationUnit declaringCompilationUnit);

        /** @return The {@link CompilationUnit} in which this top-level type is declared. */
        CompilationUnit getDeclaringCompilationUnit();
    }

    /**
     * Represents a class or interface declaration where the immediately enclosing scope is
     * another class or interface declaration.
     */
    public interface MemberTypeDeclaration extends NamedTypeDeclaration, TypeBodyDeclaration {}

    /**
     * Represents the declaration of a class or an interface that has a name. (All type
     * declarations are named, except for anonymous classes.)
     */
    public
    interface NamedTypeDeclaration extends TypeDeclaration {

        /** @return The declared (not the fully qualified) name of the class or interface */
        String getName();

        /** @return The declared type parameters */
        @Nullable TypeParameter[] getOptionalTypeParameters();
    }

    /**
     * Represents the declaration of an inner class, i.e. a class that exists in the context of
     * zero or more "enclosing instances". These are anonymous classes, local classes and member
     * classes.
     */
    interface InnerClassDeclaration extends TypeDeclaration {

        /**
         * Inner classes have zero or more synthetic fields that hold references to their enclosing
         * context:
         * <dl>
         *   <dt><code>this$<i>n</i></code></dt>
         *   <dd>
         *     (Mandatory for non-private non-static member classes; optional for private non-static
         *     member classes, local classes in non-static context, and anonymous classes in
         *     non-static context; forbidden for static member classes, local classes in static
         *     context, and anonymous classes in static context)
         *     Holds a reference to the immediately enclosing instance. <code><i>n</i></code> is
         *     N-1 for the Nth nesting level; e.g. the public non-static member class of a
         *     package member class has a synthetic field <code>this$0</code>.
         *   </dd>
         *   <dt><code>val$<i>local-variable-name</i></code></dt>
         *   <dd>
         *     (Allowed for local classes and anonymous classes; forbidden for member classes)
         *     Hold copies of <code>final</code> local variables of the defining context.
         *   </dd>
         * </dl>
         * Notice that these fields are not included in the {@link IClass.IField} array returned
         * by {@link IClass#getDeclaredIFields2()}.
         * <p>
         * If a synthetic field with the same name exists already, then it must have the same
         * type and the redefinition is ignored.
         * @param iField
         */
        void defineSyntheticField(IClass.IField iField) throws CompileException;
    }

    /** Abstract implementation of {@link TypeDeclaration}. */
    public abstract static
    class AbstractTypeDeclaration implements TypeDeclaration {
        private final Location                    location;
        private final Modifiers                   modifiers;
        @Nullable private final TypeParameter[]   optionalTypeParameters;
        private final List<MethodDeclarator>      declaredMethods              = new ArrayList();
        private final List<MemberTypeDeclaration> declaredClassesAndInterfaces = new ArrayList();
        @Nullable private Scope                   enclosingScope;

        /** Holds the resolved type during compilation. */
        @Nullable IClass resolvedType;

        public
        AbstractTypeDeclaration(
            Location                  location,
            Modifiers                 modifiers,
            @Nullable TypeParameter[] optionalTypeParameters
        ) {
            this.location               = location;
            this.modifiers              = modifiers;
            this.optionalTypeParameters = optionalTypeParameters;
        }

        @Override public short
        getModifierFlags() { return this.modifiers.accessFlags; }

        @Override public Annotation[]
        getAnnotations() { return this.modifiers.annotations; }

        /** Sets the enclosing scope of this {@link TypeDeclaration}. */
        public void
        setEnclosingScope(Scope enclosingScope) {
            if (this.enclosingScope != null && enclosingScope != this.enclosingScope) {
                throw new JaninoRuntimeException(
                    "Enclosing scope is already set for type declaration \""
                    + this.toString()
                    + "\" at "
                    + this.getLocation()
                );
            }
            this.enclosingScope = enclosingScope;
            this.modifiers.setEnclosingScope(enclosingScope);
            if (this.optionalTypeParameters != null) {
                for (TypeParameter tp : this.optionalTypeParameters) {
                    if (tp.optionalBound != null) {
                        for (ReferenceType boundType : tp.optionalBound) {
                            boundType.setEnclosingScope(enclosingScope);
                        }
                    }
                }
            }
        }

        @Nullable public TypeParameter[]
        getOptionalTypeParameters() { return this.optionalTypeParameters; }

        @Override public Scope
        getEnclosingScope() { assert this.enclosingScope != null; return this.enclosingScope; }

        /**
         * Invalidates the method cache of the {@link #resolvedType}. This is necessary when methods are added
         * <i>during</i> compilation
         */
        public void
        invalidateMethodCaches() {
            if (this.resolvedType != null) {
                this.resolvedType.invalidateMethodCaches();
            }
        }

        /** Adds one {@link MemberTypeDeclaration} to this type. */
        public void
        addMemberTypeDeclaration(MemberTypeDeclaration mcoid) {
            this.declaredClassesAndInterfaces.add(mcoid);
            mcoid.setDeclaringType(this);
        }

        /** Adds one {@link MethodDeclarator} to this type. */
        public void
        addDeclaredMethod(MethodDeclarator method) {
            this.declaredMethods.add(method);
            method.setDeclaringType(this);
        }

        // Implement TypeDeclaration.

        @Override public Collection<MemberTypeDeclaration>
        getMemberTypeDeclarations() { return this.declaredClassesAndInterfaces; }

        @Override @Nullable public MemberTypeDeclaration
        getMemberTypeDeclaration(String name) {
            for (MemberTypeDeclaration mtd : this.declaredClassesAndInterfaces) {
                if (mtd.getName().equals(name)) return mtd;
            }
            return null;
        }

        @Override @Nullable public MethodDeclarator
        getMethodDeclaration(String name) {
            for (MethodDeclarator md : this.declaredMethods) {
                if (md.name.equals(name)) return md;
            }
            return null;
        }

        @Override public List<MethodDeclarator>
        getMethodDeclarations() { return this.declaredMethods; }

        @Override public String
        createLocalTypeName(String localTypeName) {
            return (
                this.getClassName()
                + '$'
                + ++this.localClassCount
                + '$'
                + localTypeName
            );
        }

        @Override public String
        createAnonymousClassName() {
            return (
                this.getClassName()
                + '$'
                + ++this.anonymousClassCount
            );
        }

        // Implement "Locatable".

        @Override public Location
        getLocation() { return this.location; }

        @Override public void
        throwCompileException(String message) throws CompileException {
            throw new CompileException(message, this.location);
        }

        @Override public abstract String
        toString();

        /** For naming anonymous classes. */
        public int anonymousClassCount;

        /** For naming local classes. */
        public int localClassCount;
    }

    /** Base for the various class declaration kinds. */
    public abstract static
    class ClassDeclaration extends AbstractTypeDeclaration {

        /** List of {@link ConstructorDeclarator}s of this class. */
        public final List<ConstructorDeclarator> constructors = new ArrayList();

        /**
         * List of {@link TypeBodyDeclaration}s of this class: Field declarations (both static and non-static),
         * (static and non-static) initializers (a.k.a. "class initializers" and "instance initializers").
         */
        public final List<BlockStatement> variableDeclaratorsAndInitializers = new ArrayList();

        public
        ClassDeclaration(Location location, Modifiers modifiers, @Nullable TypeParameter[] optionalTypeParameters) {
            super(location, modifiers, optionalTypeParameters);
        }

        /** Adds one {@link ConstructorDeclarator} to this class. */
        public void
        addConstructor(ConstructorDeclarator cd) {
            this.constructors.add(cd);
            cd.setDeclaringType(this);
        }

        /** Adds one field declaration to this class. */
        public void
        addFieldDeclaration(FieldDeclaration fd) {
            this.variableDeclaratorsAndInitializers.add(fd);
            fd.setDeclaringType(this);

            // Clear resolved type cache.
            if (this.resolvedType != null) this.resolvedType.clearIFieldCaches();
        }

        /** Adds one initializer to this class. */
        public void
        addInitializer(Initializer i) {
            this.variableDeclaratorsAndInitializers.add(i);
            i.setDeclaringType(this);

            // Clear resolved type cache.
            if (this.resolvedType != null) this.resolvedType.clearIFieldCaches();
        }

        // Compile time members.

        // Forward-implement InnerClassDeclaration.

        /** @see Java.InnerClassDeclaration#defineSyntheticField(IClass.IField) */
        public void
        defineSyntheticField(IClass.IField iField) throws CompileException {
            if (!(this instanceof InnerClassDeclaration)) throw new JaninoRuntimeException();

            IClass.IField if2 = (IClass.IField) this.syntheticFields.get(iField.getName());
            if (if2 != null) {
                if (iField.getType() != if2.getType()) throw new JaninoRuntimeException();
                return;
            }
            this.syntheticFields.put(iField.getName(), iField);
        }

        /** @return The declared constructors, or the default constructor */
        ConstructorDeclarator[]
        getConstructors() {
            if (this.constructors.isEmpty()) {
                ConstructorDeclarator defaultConstructor = new ConstructorDeclarator(
                    this.getLocation(),                       // location
                    null,                                     // optionalDocComment
                    new Java.Modifiers(Mod.PUBLIC),           // modifiers
                    new FormalParameters(this.getLocation()), // formalParameters
                    new Type[0],                              // thrownExceptions
                    null,                                     // optionalExplicitConstructorInvocation
                    Collections.EMPTY_LIST                    // optionalStatements
                );
                defaultConstructor.setDeclaringType(this);
                return new ConstructorDeclarator[] { defaultConstructor };
            }

            return (ConstructorDeclarator[]) this.constructors.toArray(
                new ConstructorDeclarator[this.constructors.size()]
            );
        }

        /** All field names start with "this$" or "val$". */
        final SortedMap<String, IClass.IField> syntheticFields = new TreeMap();
    }

    /** Representation of a JLS7 15.9.5 'anonymous class declaration'. */
    public static final
    class AnonymousClassDeclaration extends ClassDeclaration implements InnerClassDeclaration {

        /** Base class or interface. */
        public final Type baseType;

        public
        AnonymousClassDeclaration(Location location, Type baseType) {
            super(
                location,                                         // location
                new Modifiers((short) (Mod.PRIVATE | Mod.FINAL)), // modifiers
                null                                              // optionalTypeParameters
            );
            (this.baseType = baseType).setEnclosingScope(new EnclosingScopeOfTypeDeclaration(this));
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitAnonymousClassDeclaration(this);
        }

        // Implement TypeDeclaration.

        @Override public String
        getClassName() {
            if (this.myName != null) return this.myName;

            Scope s = this.getEnclosingScope();
            for (; !(s instanceof TypeDeclaration); s = s.getEnclosingScope());
            return (this.myName = ((TypeDeclaration) s).createAnonymousClassName());
        }
        @Nullable private String myName;

        @Override public String
        toString() { return this.getClassName(); }
    }

    /** Base for the various named class declarations. */
    public abstract static
    class NamedClassDeclaration extends ClassDeclaration implements NamedTypeDeclaration, DocCommentable {

        @Nullable private final String optionalDocComment;

        /** The simple name of this class. */
        public final String  name;

        /** The type of the extended class. */
        @Nullable public final Type optionalExtendedType;

        /** The types of the implemented interfaces. */
        public final Type[] implementedTypes;

        public
        NamedClassDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            @Nullable Type            optionalExtendedType,
            Type[]                    implementedTypes
        ) {
            super(location, modifiers, optionalTypeParameters);
            this.optionalDocComment     = optionalDocComment;
            this.name                   = name;
            this.optionalExtendedType   = optionalExtendedType;
            if (optionalExtendedType != null) {
                optionalExtendedType.setEnclosingScope(new EnclosingScopeOfTypeDeclaration(this));
            }
            this.implementedTypes     = implementedTypes;
            for (Type implementedType : implementedTypes) {
                implementedType.setEnclosingScope(new EnclosingScopeOfTypeDeclaration(this));
            }
        }

        @Override public String
        toString() { return this.name; }

        // Implement NamedTypeDeclaration.

        @Override public String
        getName() { return this.name; }

        // Implement DocCommentable.

        @Override @Nullable public String
        getDocComment() { return this.optionalDocComment; }

        @Override public boolean
        hasDeprecatedDocTag() {
            return this.optionalDocComment != null && this.optionalDocComment.indexOf("@deprecated") != -1;
        }
    }

    /** Lazily determines and returns the enclosing {@link Java.Scope} of the given {@link Java.TypeDeclaration}. */
    public static final
    class EnclosingScopeOfTypeDeclaration implements Scope {

        /** The specific type declaration. */
        public final TypeDeclaration typeDeclaration;

        public
        EnclosingScopeOfTypeDeclaration(TypeDeclaration typeDeclaration) { this.typeDeclaration = typeDeclaration; }

        @Override public Scope
        getEnclosingScope() { return this.typeDeclaration.getEnclosingScope(); }
    }

    /**
     * Representation of a 'member class declaration', i.e. a class declaration that appears inside another class or
     * interface declaration.
     */
    public static
    class MemberClassDeclaration extends NamedClassDeclaration implements MemberTypeDeclaration, InnerClassDeclaration {
        public
        MemberClassDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            @Nullable Type            optionalExtendedType,
            Type[]                    implementedTypes
        ) {
            super(
                location,               // location
                optionalDocComment,     // optionalDocComment
                modifiers,              // modifiers
                name,                   // name
                optionalTypeParameters, // optionalTypeParameters
                optionalExtendedType,   // optionalExtendedType
                implementedTypes        // implementedTypes
            );
        }

        // Implement TypeBodyDeclaration.

        @Override public void
        setDeclaringType(TypeDeclaration declaringType) { this.setEnclosingScope(declaringType); }

        @Override public TypeDeclaration
        getDeclaringType() { return (TypeDeclaration) this.getEnclosingScope(); }

        @Override public boolean
        isStatic() { return Mod.isStatic(this.getModifierFlags()); }

        // Implement TypeDeclaration.

        @Override public String
        getClassName() { return this.getDeclaringType().getClassName() + '$' + this.getName(); }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX     { return visitor.visitMemberClassDeclaration(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX { return visitor.visitMemberClassDeclaration(this); }
    }

    /**
     * Representation of a 'member enum declaration', i.e. an enum declaration that appears inside another class or
     * interface declaration.
     */
    public static
    class MemberEnumDeclaration extends MemberClassDeclaration implements EnumDeclaration {

        private final List<EnumConstant> constants = new ArrayList<Java.EnumConstant>();

        public
        MemberEnumDeclaration(
            Location         location,
            @Nullable String optionalDocComment,
            Modifiers        modifiers,
            String           name,
            Type[]           implementedTypes
        ) {
            super(
                location,
                optionalDocComment,
                modifiers.add(Mod.ENUM),
                name,
                null, // optionalTypeParameters
                null, // optionalExtendedType
                implementedTypes
            );
        }

        @Override public Type[]
        getImplementedTypes() { return this.implementedTypes; }

        @Override public List<EnumConstant>
        getConstants() { return this.constants; }

        @Override public void
        addConstant(EnumConstant ec) { this.constants.add(ec); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitMemberEnumDeclaration(this);
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitMemberEnumDeclaration(this);
        }
    }

    /** Representation of a 'local class declaration' i.e. a class declaration that appears inside a method body. */
    public static final
    class LocalClassDeclaration extends NamedClassDeclaration implements InnerClassDeclaration {

        public
        LocalClassDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            @Nullable Type            optionalExtendedType,
            Type[]                    implementedTypes
        ) {
            super(
                location,               // location
                optionalDocComment,     // optionalDocComment
                modifiers,              // modifiers
                name,                   // name
                optionalTypeParameters, // optionalTypeParameters
                optionalExtendedType,   // optionalExtendedType
                implementedTypes        // implementedTypes
            );
        }

        // Implement TypeDeclaration.

        @Override public String
        getClassName() {
            for (Scope s = this.getEnclosingScope();; s = s.getEnclosingScope()) {
                if (s instanceof Java.TypeDeclaration) {
                    return ((Java.TypeDeclaration) s).getClassName() + '$' + this.name;
                }
            }
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitLocalClassDeclaration(this);
        }
    }

    /** Implementation of a 'package member class declaration', a.k.a. 'top-level class declaration'. */
    public static
    class PackageMemberClassDeclaration extends NamedClassDeclaration implements PackageMemberTypeDeclaration {

        public
        PackageMemberClassDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            @Nullable Type            optionalExtendedType,
            Type[]                    implementedTypes
        ) throws CompileException {
            super(
                location,               // location
                optionalDocComment,     // optionalDocComment
                modifiers,              // modifiers
                name,                   // name
                optionalTypeParameters, // optionalTypeParameters
                optionalExtendedType,   // optionalExtendedType
                implementedTypes        // implementedTypes
            );

            // Check for forbidden modifiers (JLS7 7.6).
            if ((modifiers.accessFlags & (Mod.PROTECTED | Mod.PRIVATE | Mod.STATIC)) != 0) {
                this.throwCompileException(
                    "Modifiers \"protected\", \"private\" and \"static\" not allowed in package member class "
                    + "declaration"
                );
            }
        }

        // Implement PackageMemberTypeDeclaration.

        @Override public void
        setDeclaringCompilationUnit(CompilationUnit declaringCompilationUnit) {
            this.setEnclosingScope(declaringCompilationUnit);
        }

        @Override public CompilationUnit
        getDeclaringCompilationUnit() { return (CompilationUnit) this.getEnclosingScope(); }

        // Implement TypeDeclaration.

        @Override public String
        getClassName() {
            String className = this.getName();

            CompilationUnit compilationUnit = (CompilationUnit) this.getEnclosingScope();

            PackageDeclaration opd = compilationUnit.optionalPackageDeclaration;
            if (opd != null) className = opd.packageName + '.' + className;

            return className;
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitPackageMemberClassDeclaration(this);
        }
    }

    public
    interface EnumDeclaration extends NamedTypeDeclaration, DocCommentable {

        @Override String getName();

        /** @return The interfaces that this enum implements */
        Type[] getImplementedTypes();

        /** @return The constants that this enum declares */
        List<EnumConstant> getConstants();

        /** Adds another constant to this enum declaration. */
        void addConstant(Java.EnumConstant ec);
    }

    public static final
    class EnumConstant extends ClassDeclaration {

        public final List<Annotation>   annotations;
        public final String             name;
        @Nullable public final Rvalue[] optionalArguments;

        public
        EnumConstant(
            Location           location,
            List<Annotation>   annotations,
            String             name,
            @Nullable Rvalue[] optionalArguments
        ) {
            super(
                location,
                new Modifiers(), // modifiers
                null             // optionalTypeParameters
            );
            this.annotations       = annotations;
            this.name              = name;
            this.optionalArguments = optionalArguments;
        }

        @Override public String
        getClassName() { return this.name; }

        @Override public Annotation[]
        getAnnotations() {
            return (Annotation[]) this.annotations.toArray(new Java.Annotation[this.annotations.size()]);
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitEnumConstant(this);
        }

        @Override public String
        toString() { return "enum " + this.name + " { ... }"; }
    }

    /** Implementation of a 'package member enum declaration', a.k.a. 'top-level enum declaration'. */
    public static final
    class PackageMemberEnumDeclaration extends PackageMemberClassDeclaration implements EnumDeclaration {

        private final List<EnumConstant> constants = new ArrayList<EnumConstant>();

        public
        PackageMemberEnumDeclaration(
            Location         location,
            @Nullable String optionalDocComment,
            Modifiers        modifiers,
            String           name,
            Type[]           implementedTypes
        ) throws CompileException {
            super(
                location,
                optionalDocComment,
                modifiers.add(Mod.ENUM),
                name,
                null, // optionalTypeParameters
                null, // optionalExtendedType
                implementedTypes
            );
        }

        @Override public Type[]
        getImplementedTypes() { return this.implementedTypes; }

        @Override public List<EnumConstant>
        getConstants() { return this.constants; }

        @Override public void
        addConstant(EnumConstant ec) { this.constants.add(ec); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitPackageMemberEnumDeclaration(this);
        }
    }

    /** Base for the various interface declaration kinds. */
    public abstract static
    class InterfaceDeclaration extends AbstractTypeDeclaration implements NamedTypeDeclaration, DocCommentable {

        @Nullable private final String optionalDocComment;

        /** The simple name of the interface. */
        public final String name;

        protected
        InterfaceDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            Type[]                    extendedTypes
        ) {
            super(location, modifiers, optionalTypeParameters);
            this.optionalDocComment = optionalDocComment;
            this.name               = name;
            this.extendedTypes      = extendedTypes;
            for (Type extendedType : extendedTypes) {
                extendedType.setEnclosingScope(new EnclosingScopeOfTypeDeclaration(this));
            }
        }

        @Override public String
        toString() { return this.name; }

        /** Adds one constant declaration to this interface declaration. */
        public void
        addConstantDeclaration(FieldDeclaration fd) {
            this.constantDeclarations.add(fd);
            fd.setDeclaringType(this);

            // Clear resolved type cache.
            if (this.resolvedType != null) this.resolvedType.clearIFieldCaches();
        }

        /** The types of the interfaces that this interface extends. */
        public final Type[] extendedTypes;

        /** The constants that this interface declares. */
        public final List<FieldDeclaration> constantDeclarations = new ArrayList();

        /** Set during "compile()". */
        @Nullable IClass[] interfaces;

        // Implement NamedTypeDeclaration.

        @Override public String
        getName() { return this.name; }

        // Implement DocCommentable.

        @Override @Nullable public String
        getDocComment() { return this.optionalDocComment; }

        @Override public boolean
        hasDeprecatedDocTag() {
            return this.optionalDocComment != null && this.optionalDocComment.indexOf("@deprecated") != -1;
        }
    }

    /**
     * Representation of a 'member interface declaration', i.e. an interface declaration that appears inside another
     * class or interface declaration.
     */
    public static
    class MemberInterfaceDeclaration
    extends InterfaceDeclaration
    implements MemberTypeDeclaration, AnnotationTypeDeclaration {

        public
        MemberInterfaceDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            Type[]                    extendedTypes
        ) {
            super(
                location,               // location
                optionalDocComment,     // optionalDocComment
                modifiers,              // modifiers
                name,                   // name
                optionalTypeParameters, // optionalTypeParameters
                extendedTypes           // extendedTypes
            );
        }

        // Implement TypeDeclaration.

        @Override public String
        getClassName() {
            NamedTypeDeclaration declaringType = (NamedTypeDeclaration) this.getEnclosingScope();
            return (
                declaringType.getClassName()
                + '$'
                + this.getName()
            );
        }

        // Implement TypeBodyDeclaration.

        @Override public void
        setDeclaringType(TypeDeclaration declaringType) { this.setEnclosingScope(declaringType); }

        @Override public TypeDeclaration
        getDeclaringType() { return (TypeDeclaration) this.getEnclosingScope(); }

        @Override public boolean
        isStatic() { return Mod.isStatic(this.getModifierFlags()); }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX     { return visitor.visitMemberInterfaceDeclaration(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX { return visitor.visitMemberInterfaceDeclaration(this); }
    }

    public static
    class MemberAnnotationTypeDeclaration extends MemberInterfaceDeclaration {

        public MemberAnnotationTypeDeclaration(
            Location         location,
            @Nullable String optionalDocComment,
            Modifiers        modifiers,
            String           name
        ) {
            super(
                location,
                optionalDocComment,
                modifiers.add(Mod.ANNOTATION),
                name,
                null,       // optionalTypeParameters
                new Type[0] // extendedTypes
            );
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitMemberAnnotationTypeDeclaration(this);
        }
    }

    /** Representation of a 'package member interface declaration', a.k.a. 'top-level interface declaration'. */
    public static
    class PackageMemberInterfaceDeclaration
    extends InterfaceDeclaration
    implements PackageMemberTypeDeclaration, AnnotationTypeDeclaration {

        public
        PackageMemberInterfaceDeclaration(
            Location                  location,
            @Nullable String          optionalDocComment,
            Modifiers                 modifiers,
            String                    name,
            @Nullable TypeParameter[] optionalTypeParameters,
            Type[]                    extendedTypes
        ) throws CompileException {
            super(
                location,               // location
                optionalDocComment,     // optionalDocComment
                modifiers,              // modifiers
                name,                   // name
                optionalTypeParameters, // optionalTypeParameters
                extendedTypes           // extendedTypes
            );

            // Check for forbidden modifiers (JLS7 7.6).
            if ((modifiers.accessFlags & (Mod.PROTECTED | Mod.PRIVATE | Mod.STATIC)) != 0) {
                this.throwCompileException(
                    "Modifiers \"protected\", \"private\" and \"static\" not allowed in package member interface "
                    + "declaration"
                );
            }
        }

        // Implement PackageMemberTypeDeclaration.

        @Override public void
        setDeclaringCompilationUnit(CompilationUnit declaringCompilationUnit) {
            this.setEnclosingScope(declaringCompilationUnit);
        }

        @Override public CompilationUnit
        getDeclaringCompilationUnit() { return (CompilationUnit) this.getEnclosingScope(); }

        // Implement TypeDeclaration.

        @Override public String
        getClassName() {
            String className = this.getName();

            CompilationUnit compilationUnit = (CompilationUnit) this.getEnclosingScope();

            PackageDeclaration opd = compilationUnit.optionalPackageDeclaration;
            if (opd != null) className = opd.packageName + '.' + className;

            return className;
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitPackageMemberInterfaceDeclaration(this);
        }
    }

    public static final
    class PackageMemberAnnotationTypeDeclaration
    extends PackageMemberInterfaceDeclaration
    implements AnnotationTypeDeclaration {

        public
        PackageMemberAnnotationTypeDeclaration(
            Location         location,
            @Nullable String optionalDocComment,
            Modifiers        modifiers,
            String           name
        ) throws CompileException {
            super(
                location,
                optionalDocComment,
                modifiers.add(Mod.ANNOTATION),
                name,
                null,       // optionalTypeParameters
                new Type[0] // extendedTypes
            );
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.TypeDeclarationVisitor<R, EX> visitor) throws EX {
            return visitor.visitPackageMemberAnnotationTypeDeclaration(this);
        }
    }

    public
    interface AnnotationTypeDeclaration extends NamedTypeDeclaration, DocCommentable {
    }

    /** Representation of a type parameter (which declares a type variable). */
    public static
    class TypeParameter {

        /** The name of the type variable. */
        public final String name;

        /** The optional bound of the type parameter. */
        @Nullable public final ReferenceType[] optionalBound;

        public
        TypeParameter(String name, @Nullable ReferenceType[] optionalBound) {
            assert name != null;
            this.name          = name;
            this.optionalBound = optionalBound;
        }

        @Override public String
        toString() {
            return (
                this.optionalBound != null
                ? this.name + " extends " + Java.join(this.optionalBound, " & ")
                : this.name
            );
        }
    }

    /**
     * Representation of a "ClassBodyDeclaration" or an "InterfaceMemberDeclaration". These are:
     * <ul>
     *   <li>Field declarators
     *   <li>Method declarators
     *   <li>Static and non-static initializers
     *   <li>Member type declarations
     * </ul>
     */
    public
    interface TypeBodyDeclaration extends Locatable, Scope {

        /** Sets the type declaration that this declaration belongs to. */
        void setDeclaringType(TypeDeclaration declaringType);

        /** @return The type declaration that this declaration belongs to. */
        TypeDeclaration getDeclaringType();

        /** @return Whether this declaration has the STATIC modifier */
        boolean isStatic();

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.TypeBodyDeclarationVisitor} for the concrete
         * {@link TypeBodyDeclaration} type.
         * @throws EX
         */
        @Nullable <R, EX extends Throwable> R
        accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX;
    }

    /** Abstract implementation of {@link TypeBodyDeclaration}. */
    public abstract static
    class AbstractTypeBodyDeclaration extends Located implements TypeBodyDeclaration {

        @Nullable private TypeDeclaration declaringType;

        /** Whether this declaration has the STATIC modifier */
        public final boolean statiC;

        protected
        AbstractTypeBodyDeclaration(Location location, boolean statiC) {
            super(location);
            this.statiC = statiC;
        }

        // Implement TypeBodyDeclaration.

        @Override public void
        setDeclaringType(TypeDeclaration declaringType) {
            if (this.declaringType != null) {
                throw new JaninoRuntimeException(
                    "Declaring type for type body declaration \""
                    + this.toString()
                    + "\"at "
                    + this.getLocation()
                    + " is already set"
                );
            }
            this.declaringType = declaringType;
        }

        @Override public TypeDeclaration
        getDeclaringType() { assert this.declaringType != null; return this.declaringType; }

        @Override public boolean
        isStatic() { return this.statiC; }

        /** Forward-implements {@link BlockStatement#setEnclosingScope(Java.Scope)}. */
        public void
        setEnclosingScope(Scope enclosingScope) {

            // Catch 22: In the initializers, some statements have their enclosing scope already set!
            if (
                enclosingScope instanceof Java.MethodDeclarator
                && "<clinit>".equals(((Java.MethodDeclarator) enclosingScope).name)
            ) {
                return;
            }

            assert this.declaringType == null;
            this.declaringType = (TypeDeclaration) enclosingScope;
        }

        // Implement 'Scope'.

        @Override public Scope
        getEnclosingScope() { assert this.declaringType != null; return this.declaringType; }
    }

    /** Representation of an 'instance initializer' (JLS7 8.6) or 'static initializer' (JLS7 8.7). */
    public static final
    class Initializer extends AbstractTypeBodyDeclaration implements BlockStatement {

        /** The block that poses the initializer. */
        public final Block block;

        public
        Initializer(Location location, boolean statiC, Block block) {
            super(location, statiC);
            (this.block = block).setEnclosingScope(this);
        }

        @Override public String
        toString() { return this.statiC ? "static " + this.block : this.block.toString(); }

        // Implement BlockStatement.

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX { return visitor.visitInitializer(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX      { return visitor.visitInitializer(this); }

        @Override @Nullable public Java.LocalVariable
        findLocalVariable(String name) { return this.block.findLocalVariable(name); }
    }

    /** Abstract base class for {@link Java.ConstructorDeclarator} and {@link Java.MethodDeclarator}. */
    public abstract static
    class FunctionDeclarator extends AbstractTypeBodyDeclaration implements Annotatable, DocCommentable {

        @Nullable private final String optionalDocComment;

        /** The {@link Modifiers} of this declarator. */
        public final Modifiers modifiers;

        /** The return type of the function (VOID for constructors). */
        public final Type type;

        /** The name of the function ({@code "<init>"} for constructors). */
        public final String name;

        /** The parameters of the function. */
        public final FormalParameters formalParameters;

        /** The types of the declared exceptions. */
        public final Type[] thrownExceptions;

        /** The statements that comprise the function; {@code null} for abstract method declarations. */
        @Nullable public final List<? extends BlockStatement> optionalStatements;

        public
        FunctionDeclarator(
            Location                                 location,
            @Nullable String                         optionalDocComment,
            Modifiers                                modifiers,
            Type                                     type,
            String                                   name,
            FormalParameters                         parameters,
            Type[]                                   thrownExceptions,
            @Nullable List<? extends BlockStatement> optionalStatements
        ) {
            super(location, Mod.isStatic(modifiers.accessFlags));
            this.optionalDocComment = optionalDocComment;
            this.modifiers          = parameters.variableArity ? modifiers.add(Mod.VARARGS) : modifiers;
            this.type               = type;
            this.name               = name;
            this.formalParameters   = parameters;
            this.thrownExceptions   = thrownExceptions;
            this.optionalStatements = optionalStatements;

            this.type.setEnclosingScope(this);
            for (FormalParameter fp : parameters.parameters) fp.type.setEnclosingScope(this);
            for (Type te : thrownExceptions) te.setEnclosingScope(this);
            if (optionalStatements != null) {
                for (Java.BlockStatement bs : optionalStatements) {

                    bs.setEnclosingScope(this);
                }
            }
        }

        /** @return The annotations of this function */
        @Override
        public Annotation[]
        getAnnotations() { return this.modifiers.annotations; }

        // Implement "FunctionDeclarator".

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.FunctionDeclaratorVisitor} for the concrete
         * {@link FunctionDeclarator} type.
         */
        @Nullable public abstract <R, EX extends Throwable> R
        accept(Visitor.FunctionDeclaratorVisitor<R, EX> visitor) throws EX;

        // Override "AbstractTypeBodyDeclaration"

        @Override public void
        setDeclaringType(TypeDeclaration declaringType) {
            super.setDeclaringType(declaringType);
            for (Annotation a : this.modifiers.annotations) a.setEnclosingScope(declaringType);
        }

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            super.setEnclosingScope(enclosingScope);
            this.modifiers.setEnclosingScope(enclosingScope);
        }

        // Implement "Scope".

        @Override public Scope
        getEnclosingScope() { return this.getDeclaringType(); }

        /** Set by "compile()". */
        @Nullable IClass returnType;

        // Implement DocCommentable.

        @Override @Nullable public String
        getDocComment() { return this.optionalDocComment; }

        @Override public boolean
        hasDeprecatedDocTag() {
            return this.optionalDocComment != null && this.optionalDocComment.indexOf("@deprecated") != -1;
        }

        /** Representation of the (formal) function parameters. */
        public static final
        class FormalParameters extends Located {

            /** The parameters of this function, but not the {@link #variableArity}. */
            public final FormalParameter[] parameters;

            /**
             * Whether this method has 'variable arity', i.e. its last parameter has an ellipsis ('...') after the
             * type.
             */
            public final boolean variableArity;

            public
            FormalParameters(Location location) { this(location, new FormalParameter[0], false); }

            public
            FormalParameters(Location location, FormalParameter[] parameters, boolean variableArity) {
                super(location);
                this.parameters    = parameters;
                this.variableArity = variableArity;
            }

            @Override public String
            toString() {
                if (this.parameters.length == 0) return "()";
                StringBuilder sb = new StringBuilder("(");
                for (int i = 0; i < this.parameters.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(this.parameters[i].toString(i == this.parameters.length - 1 && this.variableArity));
                }
                return sb.append(')').toString();
            }
        }

        /** Representation of a (formal) function parameter. */
        public static final
        class FormalParameter extends Located {

            /** Whether the parameter is declared FINAL. */
            public final boolean finaL;

            /** The type of the parameter. */
            public final Type type;

            /** The name of the parameter. */
            public final String  name;

            public
            FormalParameter(Location location, boolean finaL, Type type, String name) {
                super(location);
                this.finaL = finaL;
                this.type  = type;
                this.name  = name;
            }

            /**
             * @param hasEllipsis Whether this is the last function parameter and has an ellipsis ('...') after the
             *                    type
             */
            public String
            toString(boolean hasEllipsis) { return this.type.toString() + (hasEllipsis ? "... " : " ") + this.name; }

            @Override public String
            toString() { return this.toString(false); }

            // Compile time members.

            /** The local variable associated with this parameter. */
            @Nullable public Java.LocalVariable localVariable;
        }

        // Compile time members

        /** Mapping of variable names to {@link LocalVariable}s. */
        @Nullable public Map<String, Java.LocalVariable> localVariables;
    }

    /** Representation of a constructor declarator. */
    public static final
    class ConstructorDeclarator extends FunctionDeclarator {

        /** The resolved {@link IClass.IConstructor}. */
        @Nullable IClass.IConstructor iConstructor;

        /** The {@link AlternateConstructorInvocation} or {@link SuperConstructorInvocation}, if any. */
        @Nullable public final ConstructorInvocation optionalConstructorInvocation;

        public
        ConstructorDeclarator(
            Location                        location,
            @Nullable String                optionalDocComment,
            Modifiers                       modifiers,
            FormalParameters                parameters,
            Type[]                          thrownExceptions,
            @Nullable ConstructorInvocation optionalConstructorInvocation,
            List<BlockStatement>            statements
        ) {
            super(
                location,                                // location
                optionalDocComment,                      // optionalDocComment
                modifiers,                               // modifiers
                new BasicType(location, BasicType.VOID), // type
                "<init>",                                // name
                parameters,                              // parameters
                thrownExceptions,                        // thrownExceptions
                statements                               // optionalStatements
            );
            this.optionalConstructorInvocation = optionalConstructorInvocation;
            if (optionalConstructorInvocation != null) optionalConstructorInvocation.setEnclosingScope(this);
        }

        /** @return The {@link ClassDeclaration} where this {@link ConstructorDeclarator} appears */
        public ClassDeclaration
        getDeclaringClass() { return (ClassDeclaration) this.getEnclosingScope(); }

        // Compile time members.

        /** Synthetic parameter name to {@link Java.LocalVariable} mapping. */
        final Map<String, LocalVariable> syntheticParameters = new HashMap();

        // Implement "FunctionDeclarator":

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder(this.getDeclaringClass().getClassName()).append('(');

            FormalParameter[] fps = this.formalParameters.parameters;
            for (int i = 0; i < fps.length; ++i) {
                if (i > 0) sb.append(", ");
                sb.append(fps[i].toString(i == fps.length - 1 && this.formalParameters.variableArity));
            }
            sb.append(')');
            return sb.toString();
        }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX { return visitor.visitConstructorDeclarator(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.FunctionDeclaratorVisitor<R, EX> visitor) throws EX  { return visitor.visitConstructorDeclarator(this); }
    }

    /**
     * Representation of Java elements that can be annotated: Fields, constructors, methods, type declarations.
     */
    public
    interface Annotatable {

        /**
         * @return The annotations of this {@link TypeDeclaration}, {@link FieldDeclaration}, {@link MethodDeclarator}
         *         or {@link ConstructorDeclarator}
         */
        Annotation[] getAnnotations();
    }

    /** Representation of a method declarator. */
    public static final
    class MethodDeclarator extends FunctionDeclarator {

        public
        MethodDeclarator(
            Location                                 location,
            @Nullable String                         optionalDocComment,
            Java.Modifiers                           modifiers,
            @Nullable TypeParameter[]                optionalTypeParameters,
            Type                                     type,
            String                                   name,
            FormalParameters                         parameters,
            Type[]                                   thrownExceptions,
            @Nullable List<? extends BlockStatement> optionalStatements
        ) {
            super(
                location,           // location
                optionalDocComment, // optionalDocComment
                modifiers,          // modifiers
                type,               // type
                name,               // name
                parameters,         // parameters
                thrownExceptions,   // thrownExceptions
                optionalStatements  // optionalStatements
            );
            this.optionalTypeParameters = optionalTypeParameters;
        }

        /** @return The declared type parameters */
        @Nullable TypeParameter[]
        getOptionalTypeParameters() { return this.optionalTypeParameters; }

        @Override public void
        setDeclaringType(TypeDeclaration declaringType) {
            super.setDeclaringType(declaringType);
            if (this.optionalTypeParameters != null) {
                for (TypeParameter tp : this.optionalTypeParameters) {
                    if (tp.optionalBound != null) {
                        for (ReferenceType boundType : tp.optionalBound) {
                            boundType.setEnclosingScope(declaringType);
                        }
                    }
                }
            }
        }

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            super.setEnclosingScope(enclosingScope);
            if (this.optionalTypeParameters != null) {
                for (TypeParameter tp : this.optionalTypeParameters) {
                    if (tp.optionalBound != null) {
                        for (ReferenceType boundType : tp.optionalBound) {
                            boundType.setEnclosingScope(enclosingScope);
                        }
                    }
                }
            }
        }

        @Override public String
        toString() {
            StringBuilder     sb  = new StringBuilder(this.name).append('(');
            FormalParameter[] fps = this.formalParameters.parameters;
            for (int i = 0; i < fps.length; ++i) {
                if (i > 0) sb.append(", ");
                sb.append(fps[i].toString(i == fps.length - 1 && this.formalParameters.variableArity));
            }
            sb.append(')');
            return sb.toString();
        }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX { return visitor.visitMethodDeclarator(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.FunctionDeclaratorVisitor<R, EX> visitor) throws EX  { return visitor.visitMethodDeclarator(this); }

        @Nullable private final TypeParameter[] optionalTypeParameters;

        /** The resolved {@link IMethod}. */
        @Nullable IClass.IMethod iMethod;
    }

    /**
     * This class is derived from "Statement", because it provides for the initialization of the field.
     * In other words, "compile()" generates the code that initializes the field.
     */
    public static final
    class FieldDeclaration extends Statement implements Annotatable, TypeBodyDeclaration, DocCommentable {

        @Nullable private final String optionalDocComment;

        /** The modifiers of this field declaration. */
        public final Modifiers modifiers;

        /** The type of this field. */
        public final Type type;

        /** The declarators of this field declaration, e.g. 'int a, b;'. */
        public final VariableDeclarator[] variableDeclarators;

        public
        FieldDeclaration(
            Location             location,
            @Nullable String     optionalDocComment,
            Modifiers            modifiers,
            Type                 type,
            VariableDeclarator[] variableDeclarators
        ) {
            super(location);
            this.optionalDocComment  = optionalDocComment;
            this.modifiers           = modifiers;
            this.type                = type;
            this.variableDeclarators = variableDeclarators;

            this.type.setEnclosingScope(this);
            for (VariableDeclarator vd : variableDeclarators) {
                if (vd.optionalInitializer != null) Java.setEnclosingScope(vd.optionalInitializer, this);
            }
        }

        // Implement TypeBodyDeclaration.

        @Override public void
        setDeclaringType(TypeDeclaration declaringType) { this.setEnclosingScope(declaringType); }

        @Override public TypeDeclaration
        getDeclaringType() { return (TypeDeclaration) this.getEnclosingScope(); }

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            super.setEnclosingScope(enclosingScope);
            this.modifiers.setEnclosingScope(enclosingScope);
        }

        @Override public Annotation[]
        getAnnotations() { return this.modifiers.annotations; }

        @Override public boolean
        isStatic() { return Mod.isStatic(this.modifiers.accessFlags); }

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(Mod.shortToString(this.modifiers.accessFlags)).append(' ').append(this.type);
            sb.append(' ').append(this.variableDeclarators[0]);
            for (int i = 1; i < this.variableDeclarators.length; ++i) {
                sb.append(", ").append(this.variableDeclarators[i]);
            }
            return sb.toString();
        }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeBodyDeclarationVisitor<R, EX> visitor) throws EX { return visitor.visitFieldDeclaration(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX      { return visitor.visitFieldDeclaration(this); }

        // Implement DocCommentable.

        @Override @Nullable public String
        getDocComment() { return this.optionalDocComment; }

        @Override public boolean
        hasDeprecatedDocTag() {
            return this.optionalDocComment != null && this.optionalDocComment.indexOf("@deprecated") != -1;
        }
    }

    private static void
    setEnclosingScope(ArrayInitializerOrRvalue aiorv, Scope enclosingBlockStatement) {
        if (aiorv instanceof Rvalue) {
            ((Rvalue) aiorv).setEnclosingScope(enclosingBlockStatement);
        } else
        if (aiorv instanceof ArrayInitializer) {
            for (ArrayInitializerOrRvalue v : ((ArrayInitializer) aiorv).values) {
                Java.setEnclosingScope(v, enclosingBlockStatement);
            }
        } else
        {
            throw new JaninoRuntimeException("Unexpected array or initializer class " + aiorv.getClass().getName());
        }
    }

    /** Used by FieldDeclaration and LocalVariableDeclarationStatement. */
    public static final
    class VariableDeclarator extends Located {

        /** The name of this field or local variable. */
        public final String name;

        /** The number of '[]'s after the name. */
        public final int brackets;

        /** The initializer for the variable, if any. */
        @Nullable public final ArrayInitializerOrRvalue optionalInitializer;

        public
        VariableDeclarator(
            Location                           location,
            String                             name,
            int                                brackets,
            @Nullable ArrayInitializerOrRvalue optionalInitializer
        ) {
            super(location);
            this.name                = name;
            this.brackets            = brackets;
            this.optionalInitializer = optionalInitializer;

            // Used both by field declarations an local variable declarations, so naming conventions checking (JLS7
            // 6.4.2) cannot be done here.
        }

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder(this.name);
            for (int i = 0; i < this.brackets; ++i) sb.append("[]");
            if (this.optionalInitializer != null) sb.append(" = ").append(this.optionalInitializer);
            return sb.toString();
        }

        // Compile time members.

        /** Used only if the variable declarator declares a local variable. */
        @Nullable public LocalVariable localVariable;
    }

    /**
     * Everything that can be compiled to code, e.g. the statements occurring in the body of a method or in a block,
     * explicit constructor invocations and instance/static initializers.
     */
    public
    interface BlockStatement extends Locatable, Scope {

        /** Sets the enclosing scope of this {@link BlockStatement}. */
        void setEnclosingScope(Scope enclosingScope);

        // Implement Scope.

        @Override Scope
        getEnclosingScope();

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.BlockStatementVisitor} for the concrete
         * {@link BlockStatement} type.
         */
        @Nullable <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX;

        /** @return The local variable with the given {@code name} */
        @Nullable Java.LocalVariable
        findLocalVariable(String name);
    }

    /**
     * Everything that can occur in the body of a method or in a block. Particularly, explicit constructor invocations
     * and initializers are <b>not</b> statements in this sense.
     * <p>
     * This class is mis-named; according to JLS7 8.8.7 and 14.2, its name should be 'BlockStatement'.
     */
    public abstract static
    class Statement extends Located implements BlockStatement {

        @Nullable private Scope enclosingScope;

        protected
        Statement(Location location) { super(location); }

        // Implement "BlockStatement".

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            if (this.enclosingScope != null && enclosingScope != this.enclosingScope) {

                // Catch 22: In the initializers, some statements have their enclosing scope already set!
                if (
                    enclosingScope instanceof Java.MethodDeclarator
                    && "<clinit>".equals(((Java.MethodDeclarator) enclosingScope).name)
                ) return;

                throw new JaninoRuntimeException(

                    "Enclosing scope is already set for statement \""
                    + this.toString()
                    + "\" at "
                    + this.getLocation()
                );
            }
            this.enclosingScope = enclosingScope;
        }

        @Override public Scope
        getEnclosingScope() {
            assert this.enclosingScope != null;
            return this.enclosingScope;
        }

        // Compile time members

        /** The map of currently visible local variables. */
        @Nullable public Map<String /*name*/, Java.LocalVariable> localVariables;

        @Override @Nullable public Java.LocalVariable
        findLocalVariable(String name) {

            Map<String, LocalVariable> lvs = this.localVariables;
            if (lvs == null) { return null; }

            return (LocalVariable) lvs.get(name);
        }
    }

    /** Representation of a JLS7 14.7 'labeled statement'. */
    public static final
    class LabeledStatement extends BreakableStatement {

        /** The lael of this labeled statement. */
        public final String label;

        /** The labeled block. */
        public final Statement body;

        public
        LabeledStatement(Location location, String label, Statement body) {
            super(location);
            this.label = label;
            (this.body  = body).setEnclosingScope(this);
        }

        @Override public String
        toString() { return this.label + ": " + this.body; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitLabeledStatement(this); }
    }

    /**
     * Representation of a Java&trade; "block" (JLS7 14.2).
     * <p>
     * The statements that the block defines are executed in sequence.
     */
    public static final
    class Block extends Statement {

        /** The list of statements that comprise the body of the block. */
        public final List<BlockStatement> statements = new ArrayList();

        public
        Block(Location location) { super(location); }

        /** Adds one statement to the end of the block. */
        public void
        addStatement(BlockStatement statement) {
            this.statements.add(statement);
            statement.setEnclosingScope(this);
        }

        /** Adds a list of statements to the end of the block. */
        public void
        addStatements(List<BlockStatement> statements) {
            this.statements.addAll(statements);
            for (BlockStatement bs : statements) bs.setEnclosingScope(this);
        }

        /** @return A copy of the list of statements that comprise the body of the block */
        public BlockStatement[]
        getStatements() {
            return (BlockStatement[]) this.statements.toArray(new BlockStatement[this.statements.size()]);
        }

        // Compile time members.
        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitBlock(this); }

        @Override public String
        toString() { return "{ ... }"; }
    }

    /**
     * Base class for statements that can be terminated abnormally with a "break" statement.
     * <p>
     * According JLS7 14.15, statements that can be terminated abnormally with a "break" statement are
     * <ul>
     *   <li>{@link ContinuableStatement}s ("for", "do" and "while")
     *   <li>Labeled statements
     *   <li>"switch" statements
     * </ul>
     */
    public abstract static
    class BreakableStatement extends Statement {

        protected
        BreakableStatement(Location location) { super(location); }

        /**
         * This one's filled in by the first BREAK statement, and is {@link Offset#set()} by this breakable statement.
         */
        @Nullable CodeContext.Offset whereToBreak;
    }

    /**
     * Base class for statements that support the 'continue' statement.
     * <p>
     * According to the JLS7 14.16, these are "for", "do" and "while".
     */
    public abstract static
    class ContinuableStatement extends BreakableStatement {
        protected
        ContinuableStatement(Location location, BlockStatement body) {
            super(location);
            (this.body = body).setEnclosingScope(this);
        }

        /**
         * This one's filled in by the first CONTINUE statement, and is {@link Offset#set()} by this continuable
         * statement.
         */
        @Nullable protected CodeContext.Offset whereToContinue;

        /** The body of this continuable statement. */
        public final BlockStatement body;
    }

    /** Representation of the JLS7 14.8 'expression statement'. */
    public static final
    class ExpressionStatement extends Statement {

        /** The rvalue that is evaluated when the statement is executed. */
        public final Rvalue rvalue;

        public
        ExpressionStatement(Rvalue rvalue) throws CompileException {
            super(rvalue.getLocation());
            if (!(
                rvalue instanceof Java.Assignment
                || rvalue instanceof Java.Crement
                || rvalue instanceof Java.MethodInvocation
                || rvalue instanceof Java.SuperclassMethodInvocation
                || rvalue instanceof Java.NewClassInstance
                || rvalue instanceof Java.NewAnonymousClassInstance
            )) {
                String expressionType = rvalue.getClass().getName();
                expressionType = expressionType.substring(expressionType.lastIndexOf('.') + 1);
                this.throwCompileException(
                    expressionType
                    + " is not allowed as an expression statement. "
                    + "Expressions statements must be one of assignments, method invocations, or object allocations."
                );
            }
            (this.rvalue = rvalue).setEnclosingScope(this);
        }

        @Override public String
        toString() { return this.rvalue.toString() + ';'; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX {
            return visitor.visitExpressionStatement(this);
        }
    }

    /** Representation of the JLS7 14.3 'local class declaration statement'. */
    public static final
    class LocalClassDeclarationStatement extends Statement {

        /** The class declaration that poses the body of the statement. */
        public final LocalClassDeclaration lcd;

        public
        LocalClassDeclarationStatement(Java.LocalClassDeclaration lcd) {
            super(lcd.getLocation());
            (this.lcd = lcd).setEnclosingScope(this);
        }

        @Override public String
        toString() { return this.lcd.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX {
            return visitor.visitLocalClassDeclarationStatement(this);
        }
    }

    /** Representation of a JLS7 14.9 IF statement. */
    public static final
    class IfStatement extends Statement {

        /** The condition of the IF statement. */
        public final Rvalue condition;

        /** The 'then statement', which is executed iff the condition evaluates to TRUE. */
        public final BlockStatement thenStatement;

        /** The optional ELSE statement, which is executed iff the condition evaluates to FALSE. */
        @Nullable public final BlockStatement optionalElseStatement;

        /**
         * Notice that the <code>elseStatement</code> is mandatory; for an if statement without
         * an "else" clause, a dummy {@link Java.EmptyStatement} should be passed.
         */
        public
        IfStatement(
            Location                 location,
            Rvalue                   condition,
            BlockStatement           thenStatement,
            @Nullable BlockStatement optionalElseStatement
        ) {
            super(location);
            (this.condition             = condition).setEnclosingScope(this);
            (this.thenStatement         = thenStatement).setEnclosingScope(this);
            this.optionalElseStatement = optionalElseStatement;
            if (optionalElseStatement != null) optionalElseStatement.setEnclosingScope(this);
        }

        @Override public String
        toString() { return this.optionalElseStatement == null ? "if" : "if ... else"; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitIfStatement(this); }
    }

    /** Representation of a JLS7 14.14.1 'basic FOR statement'. */
    public static final
    class ForStatement extends ContinuableStatement {

        /** The optional 'init' part of the 'basic FOR statement'. */
        @Nullable public final BlockStatement optionalInit;

        /** The optional 'condition' part of the 'basic FOR statement'. */
        @Nullable public final Rvalue optionalCondition;

        /** The optional 'update' part of the 'basic FOR statement'. */
        @Nullable public final Rvalue[] optionalUpdate;

        public
        ForStatement(
            Location                 location,
            @Nullable BlockStatement optionalInit,
            @Nullable Rvalue         optionalCondition,
            @Nullable Rvalue[]       optionalUpdate,
            BlockStatement           body
        ) {
            super(location, body);
            this.optionalInit = optionalInit;
            if (optionalInit != null) optionalInit.setEnclosingScope(this);
            this.optionalCondition = optionalCondition;
            if (optionalCondition != null) optionalCondition.setEnclosingScope(this);
            this.optionalUpdate = optionalUpdate;
            if (optionalUpdate != null) for (Rvalue rv : optionalUpdate) rv.setEnclosingScope(this);
        }

        @Override public String
        toString() { return "for (...; ...; ...) ..."; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitForStatement(this); }
    }

    /** Representation of a JLS7 14.14.2 'enhanced FOR statement'. */
    public static final
    class ForEachStatement extends ContinuableStatement {

        /** The 'current element local variable declaration' part of the 'enhanced FOR statement'. */
        public final FormalParameter currentElement;

        /** The 'expression' part of the 'enhanced FOR statement'. */
        public final Rvalue expression;

        public
        ForEachStatement(Location location, FormalParameter currentElement, Rvalue expression, BlockStatement body) {
            super(location, body);
            (this.currentElement = currentElement).type.setEnclosingScope(this);
            (this.expression     = expression).setEnclosingScope(this);
        }

        @Override public String
        toString() { return "for (... : ...) ..."; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitForEachStatement(this); }
    }

    /** Representation of the JLS7 14.2 WHILE statement. */
    public static final
    class WhileStatement extends ContinuableStatement {

        /** The 'condition' of the WHILE statement. */
        public final Rvalue condition;

        public
        WhileStatement(Location location, Rvalue condition, BlockStatement body) {
            super(location, body);
            (this.condition = condition).setEnclosingScope(this);
        }

        @Override public String
        toString() { return "while (" + this.condition + ") " + this.body + ';'; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitWhileStatement(this); }
    }

    /** Representation of a JLS7 14.20 TRY statement. */
    public static final
    class TryStatement extends Statement {

        /** The body of the TRY statement. */
        public final BlockStatement body;

        /** The list of catch clauses (including the 'default' clause) of the TRY statement. */
        public final List<Java.CatchClause> catchClauses;

        /** The optional 'finally' block of the TRY statement. */
        @Nullable public final Block optionalFinally;

        public
        TryStatement(
            Location          location,
            BlockStatement    body,
            List<CatchClause> catchClauses,
            @Nullable Block   optionalFinally
        ) {
            super(location);
            (this.body = body).setEnclosingScope(this);
            for (CatchClause cc : (this.catchClauses = catchClauses)) cc.setEnclosingTryStatement(this);
            this.optionalFinally = optionalFinally;
            if (optionalFinally != null) optionalFinally.setEnclosingScope(this);
        }

        @Override public String
        toString() {
            return (
                "try ... "
                + this.catchClauses.size()
                + (this.optionalFinally == null ? " catches" : " catches ... finally")
            );
        }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitTryStatement(this); }

        /**
         * This one's created iff the TRY statement has a FINALLY clause when the compilation of the TRY statement
         * begins.
         */
        @Nullable CodeContext.Offset finallyOffset;
    }

    /** Representation of a JLS7 14.20.1 CATCH clause. */
    public static
    class CatchClause extends Located implements Scope {

        /** Container for the type and the name of the caught exception. */
        public final FormalParameter caughtException;

        /** Body of the CATCH clause. */
        public final Block body;

        /** Link to the enclosing TRY statement. */
        @Nullable private TryStatement enclosingTryStatement;

        // Compile time fields.

        /** Flag for catch clause reachability analysis. */
        public boolean reachable;

        public
        CatchClause(Location location, FormalParameter caughtException, Block body) {
            super(location);
            (this.caughtException = caughtException).type.setEnclosingScope(this);
            (this.body            = body).setEnclosingScope(this);
        }

        /** Links this CATCH clause to the enclosing TRY statement. */
        public void
        setEnclosingTryStatement(TryStatement enclosingTryStatement) {
            if (this.enclosingTryStatement != null && enclosingTryStatement != this.enclosingTryStatement) {
                throw new JaninoRuntimeException(
                    "Enclosing TRY statement already set for catch clause "
                    + this.toString()
                    + " at "
                    + this.getLocation()
                );
            }
            this.enclosingTryStatement = enclosingTryStatement;
        }

        @Override public Scope
        getEnclosingScope() { assert this.enclosingTryStatement != null; return this.enclosingTryStatement; }

        @Override public String
        toString() { return "catch (" + this.caughtException + ") " + this.body; }
    }

    /** The JLS7 14.10 "switch" Statement. */
    public static final
    class SwitchStatement extends BreakableStatement {

        /** The rvalue that is evaluated and matched with the CASE clauses. */
        public final Rvalue condition;

        /** The list of 'switch block statement groups' that pose the body of the SWITCH statement. */
        public final List<SwitchBlockStatementGroup> sbsgs;

        public
        SwitchStatement(Location location, Rvalue condition, List<SwitchBlockStatementGroup> sbsgs) {
            super(location);
            (this.condition = condition).setEnclosingScope(this);
            for (SwitchBlockStatementGroup sbsg : (this.sbsgs = sbsgs)) {
                for (Rvalue cl : sbsg.caseLabels) cl.setEnclosingScope(this);
                for (BlockStatement bs : sbsg.blockStatements) bs.setEnclosingScope(this);
            }
        }

        @Override public String
        toString() { return "switch (" + this.condition + ") { (" + this.sbsgs.size() + " statement groups) }"; }

        /** Representation of a 'switch block statement group' as defined in JLS7 14.11. */
        public static
        class SwitchBlockStatementGroup extends Java.Located {

            /** The CASE labels at the top of the 'switch block statement group'. */
            public final List<Rvalue> caseLabels;

            /** Whether this 'switch block statement group' includes the DEFAULT label. */
            public final boolean hasDefaultLabel;

            /** The statements following the CASE labels. */
            public final List<BlockStatement> blockStatements;

            public
            SwitchBlockStatementGroup(
                Location             location,
                List<Rvalue>         caseLabels,
                boolean              hasDefaultLabel,
                List<BlockStatement> blockStatements
            ) {
                super(location);
                this.caseLabels      = caseLabels;
                this.hasDefaultLabel = hasDefaultLabel;
                this.blockStatements = blockStatements;
            }

            @Override public String
            toString() {
                return (
                    this.caseLabels.size()
                    + (this.hasDefaultLabel ? " case label(s) plus DEFAULT" : " case label(s)")
                );
            }
        }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitSwitchStatement(this); }
    }
    static
    class Padder extends CodeContext.Inserter implements CodeContext.FixUp {

        Padder(CodeContext codeContext) { codeContext.super(); }

        @Override public void
        fixUp() {
            int x = this.offset % 4;
            if (x != 0) {
                CodeContext ca = this.getCodeContext();
                ca.pushInserter(this);
                ca.makeSpace((short) -1, 4 - x);
                ca.popInserter();
            }
        }
    }

    /** Representation of a JLS7 14.9 SYNCHRONIZED statement. */
    public static final
    class SynchronizedStatement extends Statement {

        /** The object reference on which the statement synchronizes. */
        public final Rvalue expression;

        /** The body of this SYNCHRONIZED statement. */
        public final BlockStatement body;

        public
        SynchronizedStatement(Location location, Rvalue expression, BlockStatement body) {
            super(location);
            (this.expression = expression).setEnclosingScope(this);
            (this.body       = body).setEnclosingScope(this);
        }

        @Override public String
        toString() { return "synchronized(" + this.expression + ") " + this.body; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX {
            return visitor.visitSynchronizedStatement(this);
        }

        /** The index of the local variable for the monitor object. */
        short monitorLvIndex = -1;
    }

    /** Representation of a JLS7 14.13 DO statement. */
    public static final
    class DoStatement extends ContinuableStatement {

        /** The condition in the WHILE clause of this DO statement. */
        public final Rvalue condition;

        public
        DoStatement(Location location, BlockStatement body, Rvalue condition) {
            super(location, body);
            (this.condition = condition).setEnclosingScope(this);
        }

        @Override public String
        toString() { return "do " + this.body + " while(" + this.condition + ");"; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitDoStatement(this); }
    }

    /** Representation of a JLS7 14.4 'local variable declaration statement'. */
    public static final
    class LocalVariableDeclarationStatement extends Statement {

        /** The local variable modifiers (annotations and/or flags like FINAL). */
        public final Modifiers modifiers;

        /** The declared type of the local variable. */
        public final Type type;

        /** The (one or more) 'variable declarators' that follow the type. */
        public final VariableDeclarator[] variableDeclarators;

        /** @param modifiers Only "final" allowed */
        public
        LocalVariableDeclarationStatement(
            Location             location,
            Modifiers            modifiers,
            Type                 type,
            VariableDeclarator[] variableDeclarators
        ) {
            super(location);
            this.modifiers           = modifiers;
            this.type                = type;
            this.variableDeclarators = variableDeclarators;

            this.type.setEnclosingScope(this);
            for (VariableDeclarator vd : variableDeclarators) {
                if (vd.optionalInitializer != null) Java.setEnclosingScope(vd.optionalInitializer, this);
            }
        }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX {
            return visitor.visitLocalVariableDeclarationStatement(this);
        }

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder();
            if (this.modifiers.accessFlags != Mod.NONE) {
                sb.append(Mod.shortToString(this.modifiers.accessFlags)).append(' ');
            }
            sb.append(this.type).append(' ').append(this.variableDeclarators[0].toString());
            for (int i = 1; i < this.variableDeclarators.length; ++i) {
                sb.append(", ").append(this.variableDeclarators[i].toString());
            }
            return sb.append(';').toString();
        }
    }

    /** Representation of the JLS7 14.17 RETURN statement. */
    public static final
    class ReturnStatement extends Statement {

        /** The optional rvalue that is returned. */
        @Nullable public final Rvalue optionalReturnValue;

        public
        ReturnStatement(Location location, @Nullable Rvalue optionalReturnValue) {
            super(location);
            this.optionalReturnValue = optionalReturnValue;
            if (optionalReturnValue != null) optionalReturnValue.setEnclosingScope(this);
        }

        @Override public String
        toString() { return this.optionalReturnValue == null ? "return;" : "return " + this.optionalReturnValue + ';'; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitReturnStatement(this); }
    }

    /** Representation of a JLS7 14.18 THROW statement. */
    public static final
    class ThrowStatement extends Statement {

        /** The rvalue (of type {@link Throwable}) thrown by this THROW statement. */
        public final Rvalue expression;

        public
        ThrowStatement(Location location, Rvalue expression) {
            super(location);
            (this.expression = expression).setEnclosingScope(this);
        }

        @Override public String
        toString() { return "throw " + this.expression + ';'; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitThrowStatement(this); }
    }

    /** Representation of the JLS7 14.15 BREAK statement. */
    public static final
    class BreakStatement extends Statement {

        /** The optional label that this BREAK statement refers to. */
        @Nullable public final String optionalLabel;

        public
        BreakStatement(Location location, @Nullable String optionalLabel) {
            super(location);
            this.optionalLabel = optionalLabel;
        }

        @Override public String
        toString() { return this.optionalLabel == null ? "break;" : "break " + this.optionalLabel + ';'; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitBreakStatement(this); }
    }

    /** Representation of the JLS7 14.16 CONTINUE statement. */
    public static final
    class ContinueStatement extends Statement {

        /** The optional label that this CONTINUE statement refers to. */
        @Nullable public final String optionalLabel;

        public
        ContinueStatement(Location location, @Nullable String optionalLabel) {
            super(location);
            this.optionalLabel = optionalLabel;
        }

        @Override public String
        toString() { return this.optionalLabel == null ? "continue;" : "continue " + this.optionalLabel + ';'; }

        // Compile time members:

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitContinueStatement(this); }
    }

    /** Representation of the JLS7 14.10 ASSERT statement. */
    public static final
    class AssertStatement extends Statement {

        /** The left-hand-side expression of this ASSERT statement. */
        public final Rvalue expression1;

        /** The optional right-hand-side expression of this ASSERT statement. */
        @Nullable public final Rvalue optionalExpression2;

        public
        AssertStatement(Location location, Rvalue expression1, @Nullable Rvalue optionalExpression2) {
            super(location);
            this.expression1         = expression1;
            this.optionalExpression2 = optionalExpression2;

            this.expression1.setEnclosingScope(this);
            if (this.optionalExpression2 != null) this.optionalExpression2.setEnclosingScope(this);
        }

        @Override public String
        toString() {
            return (
                this.optionalExpression2 == null
                ? "assert " + this.expression1 + ';'
                : "assert " + this.expression1 + " : " + this.optionalExpression2 + ';'
            );
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitAssertStatement(this); }
    }

    /** Representation of the "empty statement", i.e. the blank semicolon. */
    public static final
    class EmptyStatement extends Statement {

        public
        EmptyStatement(Location location) { super(location); }

        @Override public String
        toString() { return ";"; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitEmptyStatement(this); }
    }

    /** Abstract base class for {@link Java.Type}, {@link Java.Rvalue} and {@link Java.Lvalue}. */
    public abstract static
    class Atom extends Located {

        public
        Atom(Location location) { super(location); }

        /** @return This atom, converted to {@link Type}, or {@code null} if this atom is not a type */
        @Nullable public Type toType() { return null; }

        /** @return This atom, converted to {@link Rvalue}, or {@code null} if this atom is not an rvalue */
        @Nullable public Rvalue toRvalue() { return null; }

        /** @return This atom, converted to {@link Lvalue}, or {@code null} if this atom is not an lvalue */
        @Nullable public Lvalue toLvalue() { return null; }

        @Override public abstract String
        toString();

        // Parse time members:

        /**
         * @return                  This atom, converted to {@link Type}
         * @throws CompileException This atom is not a {@link Type}
         */
        public final Type
        toTypeOrCompileException() throws CompileException {

            Type result = this.toType();
            if (result != null) return result;

            throw new CompileException("Expression \"" + this.toString() + "\" is not a type", this.getLocation());
        }

        /**
         * @return                  This atom, converted to an {@link Rvalue}
         * @throws CompileException This atom is not an {@link Rvalue}
         */
        public final Rvalue
        toRvalueOrCompileException() throws CompileException {
            Rvalue result = this.toRvalue();
            if (result != null) return result;
            throw new CompileException("Expression \"" + this.toString() + "\" is not an rvalue", this.getLocation());
        }

        /**
         * @return                  This atom, converted to an {@link Lvalue}
         * @throws CompileException This atom is not a {@link Lvalue}
         */
        public final Lvalue
        toLvalueOrCompileException() throws CompileException {
            Lvalue result = this.toLvalue();
            if (result != null) return result;

            throw new CompileException("Expression \"" + this.toString() + "\" is not an lvalue", this.getLocation());

        }

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.AtomVisitor} for the concrete {@link Atom} type.
         */
        @Nullable public abstract <R, EX extends Throwable> R
        accept(Visitor.AtomVisitor<R, EX> visitor) throws EX;
    }

    /** Representation of a Java&trade; type. */
    public abstract static
    class Type extends Atom {

        @Nullable private Scope enclosingScope;

        protected
        Type(Location location) { super(location); }

        /**
         * Sets the enclosing scope for this object and all subordinate {@link org.codehaus.janino.Java.Type} objects.
         */
        public void
        setEnclosingScope(final Scope enclosingScope) {
            if (this.enclosingScope != null && enclosingScope != this.enclosingScope) {

                // Catch 22: In the initializers, some statements have their enclosing scope already set!
                if (
                    enclosingScope instanceof Java.MethodDeclarator
                    && "<clinit>".equals(((Java.MethodDeclarator) enclosingScope).name)
                ) return;

                throw new JaninoRuntimeException(
                    "Enclosing scope already set for type \""
                    + this.toString()
                    + "\" at "
                    + this.getLocation()
                );
            }
            this.enclosingScope = enclosingScope;
        }

        /** @return The enclosing scope (as previously set by {@link #setEnclosingScope(Java.Scope)}) */
        public Scope
        getEnclosingScope() { assert this.enclosingScope != null; return this.enclosingScope; }

        @Override public Type
        toType() { return this; }

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.TypeVisitor} for the concrete {@link Type} type.
         */
        @Nullable public abstract <R, EX extends Throwable> R
        accept(Visitor.TypeVisitor<R, EX> visitor) throws EX;
    }

    /** This class is not used when code is parsed; it is intended for "programmatic" types. */
    public static final
    class SimpleType extends Type {

        /** The {@link IClass} represented by this {@link Type}. */
        public final IClass iClass;

        public
        SimpleType(Location location, IClass iClass) {
            super(location);
            this.iClass = iClass;
        }

        @Override public String
        toString() { return this.iClass.toString(); }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX { return visitor.visitSimpleType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeVisitor<R, EX> visitor) throws EX { return visitor.visitSimpleType(this); }
    }

    /** Representation of a JLS7 18 "basic type" (obviously equivalent to a JLS7 4.2 "primitive type"). */
    public static final
    class BasicType extends Type {

        /** One of {@link #VOID}, {@link #BYTE} and consorts. */
        public final int index;

        public
        BasicType(Location location, int index) {
            super(location);
            this.index = index;
        }

        @Override public String
        toString() {
            switch (this.index) {
            case BasicType.VOID:
                return "void";
            case BasicType.BYTE:
                return "byte";
            case BasicType.SHORT:
                return "short";
            case BasicType.CHAR:
                return "char";
            case BasicType.INT:
                return "int";
            case BasicType.LONG:
                return "long";
            case BasicType.FLOAT:
                return "float";
            case BasicType.DOUBLE:
                return "double";
            case BasicType.BOOLEAN:
                return "boolean";
            default:
                throw new JaninoRuntimeException("Invalid index " + this.index);
            }
        }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeVisitor<R, EX> visitor) throws EX { return visitor.visitBasicType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX { return visitor.visitBasicType(this); }

        /** Value representing the VOID type. */
        public static final int VOID = 0;

        /** Value representing the BYTE type. */
        public static final int BYTE = 1;

        /** Value representing the SHORT type. */
        public static final int SHORT = 2;

        /** Value representing the CHAR type. */
        public static final int CHAR = 3;

        /** Value representing the INT type. */
        public static final int INT = 4;

        /** Value representing the LONG type. */
        public static final int LONG = 5;

        /** Value representing the FLOAT type. */
        public static final int FLOAT = 6;

        /** Value representing the DOUBLE type. */
        public static final int DOUBLE = 7;

        /** Value representing the BOOLEAN type. */
        public static final int BOOLEAN = 8;
    }

    /** representation of a JLS7 4.3 reference type. */
    public static final
    class ReferenceType extends Type implements TypeArgument {

        /** The list of (dot-separated) identifiers that pose the reference type, e.g. "java", "util", "Map". */
        public final String[] identifiers;

        /** The optional type arguments of the reference type. */
        @Nullable public final TypeArgument[] optionalTypeArguments;

        public
        ReferenceType(Location location, String[] identifiers, @Nullable TypeArgument[] optionalTypeArguments) {
            super(location);
            assert identifiers != null;
            this.identifiers           = identifiers;
            this.optionalTypeArguments = optionalTypeArguments;
        }

        @Override public String
        toString() {
            String s = Java.join(this.identifiers, ".");
            if (this.optionalTypeArguments != null) s += '<' + Java.join(this.optionalTypeArguments, ", ") + ">";
            return s;
        }

        // SUPPRESS CHECKSTYLE LineLength:3
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX { return visitor.visitReferenceType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeVisitor<R, EX> visitor) throws EX { return visitor.visitReferenceType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(TypeArgumentVisitor<R, EX> visitor) throws EX { return visitor.visitReferenceType(this); }
    }

    /** Representation of a JLS7 4.5.1 type argument. */
    public
    interface TypeArgument {

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.TypeArgumentVisitor} for the concrete {@link
         * TypeArgument} type.
         */
        @Nullable <R, EX extends Throwable> R
        accept(Visitor.TypeArgumentVisitor<R, EX> visitor) throws EX;
    }

    /**
     * Representation of the first part of a JLS7 15.9 'Qualified class instance creation expression': The 'a.new
     * MyClass' part of 'a.new MyClass(...)'.
     */
    public static final
    class RvalueMemberType extends Type {

        /** The expression that represents the outer instance required for the instantiation of the inner type. */
        public final Rvalue rvalue;

        /** The simple name of the inner type being instantiated. */
        public final String identifier;

        /** Notice: The {@code rvalue} is not a subordinate object! */
        public
        RvalueMemberType(Location location, Rvalue rvalue, String identifier) {
            super(location);
            this.rvalue     = rvalue;
            this.identifier = identifier;
        }

        @Override public String
        toString() { return this.identifier; }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX { return visitor.visitRvalueMemberType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeVisitor<R, EX> visitor) throws EX { return visitor.visitRvalueMemberType(this); }
    }

    /** Representation of a JLS7 10.1 'array type'. */
    public static final
    class ArrayType extends Type implements TypeArgument {

        /** The (declared) type of the array's components. */
        public final Type componentType;

        public
        ArrayType(Type componentType) {
            super(componentType.getLocation());
            this.componentType = componentType;
        }

        @Override public void
        setEnclosingScope(final Scope enclosingScope) {
            super.setEnclosingScope(enclosingScope);
            this.componentType.setEnclosingScope(enclosingScope);
        }

        @Override public String
        toString() { return this.componentType.toString() + "[]"; }

        // SUPPRESS CHECKSTYLE LineLength:3
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX { return visitor.visitArrayType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.TypeVisitor<R, EX> visitor) throws EX { return visitor.visitArrayType(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(TypeArgumentVisitor<R, EX> visitor) throws EX { return visitor.visitArrayType(this); }
    }

    /**
     * Representation of an "rvalue", i.e. an expression that has a type and
     * a value, but cannot be assigned to: An expression that can be the
     * right-hand-side of an assignment.
     */
    public abstract static
    class Rvalue extends Atom implements ArrayInitializerOrRvalue, ElementValue {

        @Nullable private Java.Scope enclosingScope;

        protected
        Rvalue(Location location) { super(location); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(ElementValueVisitor<R, EX> visitor) throws EX { return visitor.visitRvalue(this); }


        /** Sets the enclosing scope for this object and all subordinate {@link Java.Rvalue} objects. */
        @Override
        public final void
        setEnclosingScope(final Java.Scope enclosingScope) {
            this.accept((Visitor.RvalueVisitor<Void, RuntimeException>) new Traverser<RuntimeException>() {

                @Override public void
                traverseRvalue(Java.Rvalue rv) {
                    if (rv.enclosingScope != null && enclosingScope != rv.enclosingScope) {
                        throw new JaninoRuntimeException(
                            "Enclosing block statement for rvalue \""
                            + rv
                            + "\" at "
                            + rv.getLocation()
                            + " is already set"
                        );
                    }
                    rv.enclosingScope = enclosingScope;
                    super.traverseRvalue(rv);
                }
                @Override public void
                traverseAnonymousClassDeclaration(Java.AnonymousClassDeclaration acd) {
                    acd.setEnclosingScope(enclosingScope);
                    ;
                }
                @Override public void
                traverseType(Java.Type t) {
                    if (t.enclosingScope != null && enclosingScope != t.enclosingScope) {
                        throw new JaninoRuntimeException(
                            "Enclosing scope already set for type \""
                            + this.toString()
                            + "\" at "
                            + t.getLocation()
                        );
                    }
                    t.enclosingScope = enclosingScope;
//                    t.setEnclosingScope(enclosingScope);
                    super.traverseType(t);
                }
            }.comprehensiveVisitor());
        }

        /**
         * @return The enclosing scope, as set with {@link #setEnclosingScope(Java.Scope)}
         */
        public Java.Scope
        getEnclosingScope() {
            assert this.enclosingScope != null;
            return this.enclosingScope;
        }

        /**
         * @return The enclosing scope, as set with {@link #setEnclosingScope(Java.Scope)}
         */
        @Nullable public Java.Scope
        getEnclosingScopeOrNull() { return this.enclosingScope; }

        @Override @Nullable public Rvalue
        toRvalue() { return this; }

        /**
         * The special value for the {@link #constantValue} field indicating that the constant value of this rvalue
         * has not yet been determined.
         */
        static final Object CONSTANT_VALUE_UNKNOWN = new Object() {

            @Override public String
            toString() { return "CONSTANT_VALUE_UNKNOWN"; }
        };

        /**
         * The constant value of this rvalue, or {@link #CONSTANT_VALUE_UNKNOWN} iff the constant value of this rvalue
         * has not yet been determined.
         */
        @Nullable Object constantValue = Java.Rvalue.CONSTANT_VALUE_UNKNOWN;

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.RvalueVisitor} for the concrete {@link Rvalue}
         * type.
         */
        @Nullable public abstract <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> rvv) throws EX;

        @Override @Nullable public final <R, EX extends Throwable> R
        accept(Visitor.AtomVisitor<R, EX> visitor) throws EX {
            return this.accept((Visitor.RvalueVisitor<R, EX>) visitor);
        }
    }

    /** Base class for {@link Java.Rvalue}s that compile better as conditional branches. */
    public abstract static
    class BooleanRvalue extends Rvalue {
        protected BooleanRvalue(Location location) { super(location); }
    }

    /**
     * Representation of an "lvalue", i.e. an expression that has a type and
     * a value, and can be assigned to: An expression that can be the
     * left-hand-side of an assignment.
     */
    public abstract static
    class Lvalue extends Rvalue {
        protected Lvalue(Location location) { super(location); }

        @Override @Nullable public Lvalue
        toLvalue() { return this; }

        /**
         * Invokes the '{@code visit...()}' method of {@link Visitor.LvalueVisitor} for the concrete {@link Lvalue}
         * type.
         */
        @Nullable public abstract <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> lvv) throws EX;

        @Override @Nullable public final <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX {
            return this.accept((Visitor.LvalueVisitor<R, EX>) visitor);
        }

    }

    /**
     * Representation of a JLS7 6.5.2 'ambiguous name'.
     * <p>
     * This class is special: It does not extend/implement the {@link Atom} subclasses, but overrides {@link Atom}'s
     * "{@code to...()}" methods.
     */
    public static final
    class AmbiguousName extends Lvalue {

        /** The first {@link #n} of these identifiers comprise this ambiguous name. */
        public final String[] identifiers;

        /** @see #identifiers */
        public final int n;

        public
        AmbiguousName(Location location, String[] identifiers) {
            this(location, identifiers, identifiers.length);
        }
        public
        AmbiguousName(Location location, String[] identifiers, int n) {
            super(location);
            this.identifiers = identifiers;
            this.n           = n;
        }

        // Override "Atom.toType()".
        @Nullable private Type type;

        @Override public Type
        toType() {
            if (this.type != null) return this.type;

            String[] is = new String[this.n];
            System.arraycopy(this.identifiers, 0, is, 0, this.n);

            Type result = new ReferenceType(this.getLocation(), is, null);

            Scope es = this.getEnclosingScopeOrNull();
            if (es != null) result.setEnclosingScope(es);

            return (this.type = result);
        }

        // Compile time members.

        @Override public String
        toString() { return Java.join(this.identifiers, ".", 0, this.n); }

        @Override @Nullable public Lvalue
        toLvalue() {
            if (this.reclassified != null) { return this.reclassified.toLvalue(); }
            return this;
        }

        @Override @Nullable public Rvalue
        toRvalue() {
            if (this.reclassified != null) { return this.reclassified.toRvalue(); }
            return this;
        }

        /** The result of 'ambiguous name resolution' furing compilation. */
        @Nullable Atom reclassified;

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX { return visitor.visitAmbiguousName(this); }
    }

    /** Representation of a JLS7 6.5.2.1.5 'package name'. */
    public static final
    class Package extends Atom {

        /** The complete name of a package, e.g. 'java' or 'java.util'. */
        public final String name;

        public
        Package(Location location, String name) {
            super(location);
            this.name = name;
        }

        @Override public String
        toString() { return this.name; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.AtomVisitor<R, EX> visitor) throws EX { return visitor.visitPackage(this); }
    }

    /** Representation of a local variable access -- used during compilation. */
    public static final
    class LocalVariableAccess extends Lvalue {

        /** The local variable that is accessed. */
        public final LocalVariable localVariable;

        public
        LocalVariableAccess(Location location, LocalVariable localVariable) {
            super(location);
            this.localVariable = localVariable;
        }

        // Compile time members.

        @Override public String
        toString() { return this.localVariable.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX { return visitor.visitLocalVariableAccess(this); }
    }

    /**
     * Representation of an access to a field of a class or an interface. (Does not implement the {@link ArrayLength},
     * e.g. "myArray.length".)
     */
    public static final
    class FieldAccess extends Lvalue {

        /**
         * The left-hand-side of the field access - either a <i>type</i> or an <i>rvalue</i> (which includes all
         * lvalues).
         */
        public final Atom lhs;

        /** The field within the class or instance identified by the {@link #lhs}. */
        public final IClass.IField field;

        public
        FieldAccess(Location location, Atom lhs, IClass.IField field) {
            super(location);
            this.lhs   = lhs;
            this.field = field;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + '.' + this.field.getName(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX { return visitor.visitFieldAccess(this); }
    }

    /** Representation of the JLS7 10.7 array type 'length' pseudo-member. */
    public static final
    class ArrayLength extends Rvalue {

        /** The rvalue identifying the array to determine the length of. */
        public final Rvalue lhs;

        public
        ArrayLength(Location location, Rvalue lhs) {
            super(location);
            this.lhs = lhs;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + ".length"; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitArrayLength(this); }
    }

    /** Representation of an JLS7 15.8.3 access to the innermost enclosing instance. */
    public static final
    class ThisReference extends Rvalue {

        public
        ThisReference(Location location) { super(location); }

        // Compile time members.

        /** A cache for the type of the instance that 'this' refers to. */
        @Nullable IClass iClass;

        // Implement "Atom".

        @Override public String
        toString() { return "this"; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitThisReference(this); }
    }

    /** Representation of an JLS7 15.8.4 access to the current object or an enclosing instance. */
    public static final
    class QualifiedThisReference extends Rvalue {

        /** The qualification left from the 'this' keyword. */
        public final Type qualification;

        public
        QualifiedThisReference(Location location, Type qualification) {
            super(location);
            this.qualification = qualification;
        }

        // Compile time members.

        /** The innermost enclosing class declaration. */
        @Nullable ClassDeclaration declaringClass;

        /**
         * The innermost 'type body declaration' enclosing this 'qualified this reference', i.e. the method,
         * type initializer or field initializer.
         */
        @Nullable TypeBodyDeclaration declaringTypeBodyDeclaration;

        /** The resolved {@link #qualification}. */
        @Nullable IClass targetIClass;

        // Implement "Atom".

        @Override public String
        toString() { return this.qualification.toString() + ".this"; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitQualifiedThisReference(this); }
    }

    /** Representation of a JLS7 15.8.2 'class literal'. */
    public static final
    class ClassLiteral extends Rvalue {

        /** The type left of the '.class' suffix. */
        public final Type type;

        public
        ClassLiteral(Location location, Type type) {
            super(location);
            this.type = type;
        }

        // Implement "Atom".

        @Override public String
        toString() { return this.type.toString() + ".class"; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitClassLiteral(this); }
    }

    /** Representation of all JLS7 15.26 assignments. */
    public static final
    class Assignment extends Rvalue {

        /** The lvalue to assign to. */
        public final Lvalue lhs;

        /**
         * The assignment operator; either the 'simple assignment operator ('=', JLS7 15.26.1) or one of the 'compound
         * assignment operators (JLS7 15.26.2).
         */
        public final String operator;

        /** The rvalue that is assigned. */
        public final Rvalue rhs;

        public
        Assignment(Location location, Lvalue lhs, String operator, Rvalue rhs) {
            super(location);
            this.lhs      = lhs;
            this.operator = operator;
            this.rhs      = rhs;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + ' ' + this.operator + ' ' + this.rhs.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitAssignment(this); }
    }

    /** Representation of a JLS7 15.25 'conditional operation'. */
    public static final
    class ConditionalExpression extends Rvalue {

        /** Left-hand side of this conditional operation. */
        public final Rvalue lhs;

        /** Middle-hand side of this conditional operation. */
        public final Rvalue mhs;

        /** Right-hand side of this conditional operation. */
        public final Rvalue rhs;

        public
        ConditionalExpression(Location location, Rvalue lhs, Rvalue mhs, Rvalue rhs) {
            super(location);
            this.lhs = lhs;
            this.mhs = mhs;
            this.rhs = rhs;
        }

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + " ? " + this.mhs.toString() + " : " + this.rhs.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitConditionalExpression(this); }
    }

    /**
     * Representation of a JLS7 15.14.2 'postfix increment operation', a JLS7 15.14.3 'postfix decrement operation', a
     * JLS7 15.15.1 'prefix increment operation' or a JLS7 15.15.2 'prefix decrement operation'.
     */
    public static final
    class Crement extends Rvalue {

        /** Whether this operation is 'pre' (TRUE) or 'post' (FALSE). */
        public final boolean pre;

        /** The operator; either "++" or "--". */
        public final String operator;

        /** The lvalue to operate upon. */
        public final Lvalue operand;

        public
        Crement(Location location, String operator, Lvalue operand) {
            super(location);
            this.pre      = true;
            this.operator = operator;
            this.operand  = operand;
        }
        public
        Crement(Location location, Lvalue operand, String operator) {
            super(location);
            this.pre      = false;
            this.operator = operator;
            this.operand  = operand;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return this.pre ? this.operator + this.operand : this.operand + this.operator; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitCrement(this); }
    }

    /** Representation of a JLS7 15.13 'array access expression'. */
    public static final
    class ArrayAccessExpression extends Lvalue {

        /** The array to access (must be an {@link Lvalue} if the access is modifying). */
        public final Rvalue lhs;

        /** The index value to use. */
        public final Rvalue index;

        public
        ArrayAccessExpression(Location location, Rvalue lhs, Rvalue index) {
            super(location);
            this.lhs   = lhs;
            this.index = index;
        }

        // Compile time members:

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + '[' + this.index + ']'; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX { return visitor.visitArrayAccessExpression(this); }
    }

    /** Representation of a JLS7 15.11 'field access expression', including the "array length" pseudo field access. */
    public static final
    class FieldAccessExpression extends Lvalue {

        /** {@link Type}, {@link Rvalue} or {@link Lvalue} to operate upon. */
        public final Atom lhs;

        /** Name of the field within the {@link #lhs} to access. */
        public final String fieldName;

        public
        FieldAccessExpression(Location location, Atom lhs, String fieldName) {
            super(location);
            this.lhs       = lhs;
            this.fieldName = fieldName;
        }

        // Compile time members:

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + '.' + this.fieldName; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX { return visitor.visitFieldAccessExpression(this); }

        /** The {@link ArrayLength} or {@link FieldAccess} resulting from this 'field access expression'. */
        @Nullable Rvalue value;
    }

    /** Representation of an JLS7 'superclass field access expression', e.g. "super.fld" and "Type.super.fld". */
    public static final
    class SuperclassFieldAccessExpression extends Lvalue {

        /** The optional qualification before '.super.fld'. */
        @Nullable public final Type optionalQualification;

        /** The name of the field to access. */
        public final String fieldName;

        public
        SuperclassFieldAccessExpression(Location location, @Nullable Type optionalQualification, String fieldName) {
            super(location);
            this.optionalQualification = optionalQualification;
            this.fieldName             = fieldName;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() {
            return (
                this.optionalQualification != null
                ? this.optionalQualification.toString() + ".super."
                : "super."
            ) + this.fieldName;
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX {
            return visitor.visitSuperclassFieldAccessExpression(this);
        }

        /** The {@link FieldAccess} that implements this {@link FieldAccessExpression}. */
        @Nullable Rvalue value;
    }

    /**
     * Representation of a JLS7 15.15.3 'unary plus operator', a JLS7 15.15.4 'unary minus operator', a JLS7 15.15.5
     * 'bitwise complement operator' or a JLS7 15.15.6 'logical complement operator'.
     */
    public static final
    class UnaryOperation extends BooleanRvalue {

        /** The operator (either "+", "-", "~" or "!"). */
        public final String operator;

        /** The rvalue to operate upon. */
        public final Rvalue operand;

        public
        UnaryOperation(Location location, String operator, Rvalue operand) {
            super(location);
            this.operator = operator;
            this.operand  = operand;
        }

        // Implement "Atom".

        @Override public String
        toString() { return this.operator + this.operand.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitUnaryOperation(this); }
    }

    /** Representation of a JLS7 15.20.2 'type comparison operation'. */
    public static final
    class Instanceof extends Rvalue {

        /** The rvalue who's type is to be compared. */
        public final Rvalue lhs;

        /** The type that the {@link #lhs} is checked against. */
        public final Type rhs;

        public
        Instanceof(Location location, Rvalue lhs, Type rhs) {
            super(location);
            this.lhs = lhs;
            this.rhs = rhs;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + " instanceof " + this.rhs.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitInstanceof(this); }
    }

    /**
     * Representation of all non-operand-modifying binary operations.
     * <p>
     * Operations with boolean result:
     * <dl>
     *   <dt>||
     *   <dd>JLS7 15.24 'conditional or operation'
     *   <dt>&amp;&amp;
     *   <dd>JLS7 15.23 'conditional and operation'
     *   <dt>==
     *   <dd>JLS7 15.21 'equality operation'
     *   <dt>!=
     *   <dd>JLS7 15.22 'non-equality operation'
     *   <dt>&lt; &gt; &lt;= &gt;=
     *   <dd>JLS7 15.20.1 'numerical comparison operations'
     * </dl>
     * Operations with non-boolean result:
     * <dl>
     *   <dt>|
     *   <dd>JLS7 15.22.1 'integer bitwise OR operation' and JLS7 15.22.2 'boolean logical OR operation'
     *   <dt>^
     *   <dd>JLS7 15.22.1 'integer bitwise XOR operation' and JLS7 15.22.2 'boolean logical XOR operation'
     *   <dt>&amp;
     *   <dd>JLS7 15.22.1 'integer bitwise AND operation' and JLS7 15.22.2 'boolean logical AND operation'
     *   <dt>* / %
     *   <dd>JLS7 15.17 'multiplicative operations'
     *   <dt>+ -
     *   <dd>JLS7 15.18 'additive operations'
     *   <dt>&lt;&lt; &gt;&gt; &gt;&gt;&gt;
     *   <dd>JLS7 15.19 'shift operations'
     * </dl>
     */
    public static final
    class BinaryOperation extends BooleanRvalue {

        /** The left hand side operand. */
        public final Rvalue lhs;

        /** The operator; one of thos described in {@link BinaryOperation}. */
        public final String op;

        /** The right hand side operand. */
        public final Rvalue rhs;

        public
        BinaryOperation(Location location, Rvalue lhs, String op, Rvalue rhs) {
            super(location);
            this.lhs = lhs;
            this.op  = op;
            this.rhs = rhs;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return this.lhs.toString() + ' ' + this.op + ' ' + this.rhs.toString(); }

        /** Returns an {@link Iterator} over a left-to-right sequence of {@link Java.Rvalue}s. */
        public Iterator<Rvalue>
        unrollLeftAssociation() {
            List<Rvalue>    operands = new ArrayList();
            BinaryOperation bo       = this;
            for (;;) {
                operands.add(bo.rhs);
                Rvalue lhs = bo.lhs;
                if (lhs instanceof BinaryOperation && ((BinaryOperation) lhs).op == this.op) {
                    bo = (BinaryOperation) lhs;
                } else {
                    operands.add(lhs);
                    break;
                }
            }
            return new ReverseListIterator(operands.listIterator(operands.size()));
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitBinaryOperation(this); }
    }

    /** Representation of a JLS7 15.16 'cast expression'. */
    public static final
    class Cast extends Rvalue {

        /** The type to convert to. */
        public final Type targetType;

        /** The rvalue to convert. */
        public final Rvalue value;

        public
        Cast(Location location, Type targetType, Rvalue value) {
            super(location);
            this.targetType = targetType;
            this.value      = value;
        }

        // Compile time members.

        // Implement "Atom".

        @Override public String
        toString() { return '(' + this.targetType.toString() + ") " + this.value.toString(); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitCast(this); }
    }

    /** Representation of a JLS7 15.8.5 'parenthesized expression'. */
    public static final
    class ParenthesizedExpression extends Lvalue {

        /** The rvalue in parentheses. */
        public final Rvalue value;

        public
        ParenthesizedExpression(Location location, Rvalue value) {
            super(location);
            this.value = value;
        }

        // Implement 'Atom'.

        @Override public String
        toString() { return '(' + this.value.toString() + ')'; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.LvalueVisitor<R, EX> visitor) throws EX { return visitor.visitParenthesizedExpression(this); }
    }

    /** Abstract bas class for {@link SuperConstructorInvocation} and {@link AlternateConstructorInvocation}. */
    public abstract static
    class ConstructorInvocation extends Atom implements BlockStatement {

        /** The arguments to pass to the constructor. */
        public final Rvalue[] arguments;

        @Nullable private Scope enclosingScope;

        protected
        ConstructorInvocation(Location location, Rvalue[] arguments) {
            super(location);
            this.arguments = arguments;
            for (Rvalue a : arguments) a.setEnclosingScope(this);
        }

        // Implement BlockStatement

        @Override public void
        setEnclosingScope(Scope enclosingScope) {
            if (this.enclosingScope != null) {
                throw new JaninoRuntimeException(
                    "Enclosing scope is already set for statement \""
                    + this.toString()
                    + "\" at "
                    + this.getLocation()
                );
            }
            this.enclosingScope = enclosingScope;
        }

        @Override public Scope
        getEnclosingScope() { assert this.enclosingScope != null; return this.enclosingScope; }

        /** The local variables that are accessible during the compilation of the constructor invocation. */
        @Nullable public Map<String /*name*/, Java.LocalVariable> localVariables;

        @Override @Nullable public Java.LocalVariable
        findLocalVariable(String name) {
            if (this.localVariables != null) return (LocalVariable) this.localVariables.get(name);
            return null;
        }
    }

    /** Representation of a JLS7 8.8.7.1. 'alternate constructor invocation'. */
    public static final
    class AlternateConstructorInvocation extends ConstructorInvocation {

        public
        AlternateConstructorInvocation(Location location, Rvalue[] arguments) { super(location, arguments); }

        // Implement Atom.

        @Override public String
        toString() { return "this()"; }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX           { return ((Visitor.BlockStatementVisitor<R, EX>) visitor).visitAlternateConstructorInvocation(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitAlternateConstructorInvocation(this); }
    }

    /** Representation of a JLS7 8.8.7.1. 'superclass constructor invocation'. */
    public static final
    class SuperConstructorInvocation extends ConstructorInvocation {

        /**
         * The qualification for this 'qualified superclass constructor invocation', or {@code null} iff this is an
         * 'unqualified superclass constructor invocation'.
         */
        @Nullable public final Rvalue optionalQualification;

        public
        SuperConstructorInvocation(Location location, @Nullable Rvalue optionalQualification, Rvalue[] arguments) {
            super(location, arguments);
            this.optionalQualification = optionalQualification;
            if (optionalQualification != null) optionalQualification.setEnclosingScope(this);
        }

        // Implement Atom.

        @Override public String
        toString() { return "super()"; }

        // SUPPRESS CHECKSTYLE LineLength:2
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.AtomVisitor<R, EX> visitor) throws EX           { return ((Visitor.BlockStatementVisitor<R, EX>) visitor).visitSuperConstructorInvocation(this); }
        @Override @Nullable public <R, EX extends Throwable> R accept(Visitor.BlockStatementVisitor<R, EX> visitor) throws EX { return visitor.visitSuperConstructorInvocation(this); }
    }

    /** Representation of a JLS7 15.12 'method invocation expression'. */
    public static final
    class MethodInvocation extends Invocation {

        /** The optional type or rvalue that qualifies this method invocation. */
        @Nullable public final Atom optionalTarget;

        public
        MethodInvocation(Location location, @Nullable Atom optionalTarget, String methodName, Rvalue[] arguments) {
            super(location, methodName, arguments);
            this.optionalTarget = optionalTarget;
        }

        // Implement "Atom".

        /** The resolved {@link IMethod}. */
        @Nullable IClass.IMethod iMethod;

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder();
            if (this.optionalTarget != null) sb.append(this.optionalTarget.toString()).append('.');
            sb.append(this.methodName).append('(');
            for (int i = 0; i < this.arguments.length; ++i) {
                if (i > 0) sb.append(", ");
                sb.append(this.arguments[i].toString());
            }
            sb.append(')');
            return sb.toString();
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitMethodInvocation(this); }
    }

    /** Representation of a JLS7 15.12.1.1.3 'superclass method invocation'. */
    public static final
    class SuperclassMethodInvocation extends Invocation {

        public
        SuperclassMethodInvocation(Location location, String methodName, Rvalue[] arguments) {
            super(location, methodName, arguments);
        }

        // Implement "Atom".

        @Override public String
        toString() { return "super." + this.methodName + "()"; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitSuperclassMethodInvocation(this); }
    }

    /** Abstract base class for {@link MethodInvocation} and {@link SuperclassMethodInvocation}. */
    public abstract static
    class Invocation extends Rvalue {

        /** name of the invoked method. */
        public final String methodName;

        /** Arguments to pass to the method. */
        public final Rvalue[] arguments;

        protected
        Invocation(Location location, String methodName, Rvalue[] arguments) {
            super(location);
            this.methodName = methodName;
            this.arguments  = arguments;
        }
    }

    /** Representation of a JLS7 'class instance creation expression'. */
    public static final
    class NewClassInstance extends Rvalue {

        /** The qualification of this 'qualified class instance creation expression'. */
        @Nullable public final Rvalue optionalQualification;

        /** The type to instantiate. */
        @Nullable public final Type type;

        /** The arguments to pass to the constructor. */
        public final Rvalue[] arguments;

        public
        NewClassInstance(Location location, @Nullable Rvalue optionalQualification, Type type, Rvalue[] arguments) {
            super(location);
            this.optionalQualification = optionalQualification;
            this.type                  = type;
            this.arguments             = arguments;
        }

        // Compile time members.

        /** The resolved {@link #type}. */
        @Nullable protected IClass iClass;

        public
        NewClassInstance(Location location, @Nullable Rvalue optionalQualification, IClass iClass, Rvalue[] arguments) {
            super(location);
            this.optionalQualification = optionalQualification;
            this.type                  = null;
            this.arguments             = arguments;
            this.iClass                = iClass;
        }

        // Implement "Atom".

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder();
            if (this.optionalQualification != null) sb.append(this.optionalQualification.toString()).append('.');
            sb.append("new ");
            if (this.type != null) {
                sb.append(this.type.toString());
            } else
            if (this.iClass != null) {
                sb.append(this.iClass.toString());
            } else {
                sb.append("???");
            }
            sb.append('(');
            for (int i = 0; i < this.arguments.length; ++i) {
                if (i > 0) sb.append(", ");
                sb.append(this.arguments[i].toString());
            }
            sb.append(')');
            return sb.toString();
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitNewClassInstance(this); }
    }

    /** Representation of a JLS7 15.9 'anonymous class instance creation expression'. */
    public static final
    class NewAnonymousClassInstance extends Rvalue {

        /** The qualification iff this a 'qualified anonymous class instance creation expression'. */
        @Nullable public final Rvalue optionalQualification;

        /** The declaration of the anonymous class to instantiate. */
        public final AnonymousClassDeclaration anonymousClassDeclaration;

        /** The arguments to pass to the constructor. */
        public final Rvalue[] arguments;

        public
        NewAnonymousClassInstance(
            Location                  location,
            @Nullable Rvalue          optionalQualification,
            AnonymousClassDeclaration anonymousClassDeclaration,
            Rvalue[]                  arguments
        ) {
            super(location);
            this.optionalQualification     = optionalQualification;
            this.anonymousClassDeclaration = anonymousClassDeclaration;
            this.arguments                 = arguments;
        }

        // Implement "Atom".

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder();
            if (this.optionalQualification != null) sb.append(this.optionalQualification.toString()).append('.');
            sb.append("new ").append(this.anonymousClassDeclaration.baseType.toString()).append("() { ... }");
            return sb.toString();
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitNewAnonymousClassInstance(this); }
    }

    /** 'Artificial' operation for accessing the parameters of the synthetic constructor of an anonymous class. */
    public static final
    class ParameterAccess extends Rvalue {

        /** The parameter to access. */
        public final FormalParameter formalParameter;

        public
        ParameterAccess(Location location, FormalParameter formalParameter) {
            super(location);
            this.formalParameter = formalParameter;
        }

        // Implement Atom

        @Override public String
        toString() { return this.formalParameter.name; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitParameterAccess(this); }
    }

    /** Representation of a JLS7 15.10 'array creation expression'. */
    public static final
    class NewArray extends Rvalue {

        /**
         * The component type of the ({@link #dimExprs}{@code .length + }{@link #dims})-dimensional array to
         * instantiate.
         */
        public final Type type;

        /** The sizes of the first dimensions to instantiate. */
        public final Rvalue[] dimExprs;

        /** The count of additional dimensions that the array should have. */
        public final int dims;

        /**
         * Create a new array with dimension dimExprs.length + dims
         * <p>
         * e.g. byte[12][][] is created with
         * <pre>
         *     new NewArray(
         *         null,
         *         Java.BasicType(NULL, Java.BasicType.BYTE),
         *         new Rvalue[] { new Java.Literal(null, Integer.valueOf(12) },
         *         2
         *     )
         * </pre>
         *
         * @param location  the location of this element
         * @param type      the base type of the array
         * @param dimExprs  sizes for dimensions being allocated with specific sizes
         * @param dims      the number of dimensions that are not yet allocated
         */
        public
        NewArray(Location location, Type type, Rvalue[] dimExprs, int dims) {
            super(location);
            this.type     = type;
            this.dimExprs = dimExprs;
            this.dims     = dims;
        }

        // Implement "Atom".

        @Override public String
        toString()  { return "new " + this.type.toString() + "[]..."; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitNewArray(this); }
    }

    /** Representation of a JLS7 15.10 'array creation expression'. */
    public static final
    class NewInitializedArray extends Rvalue {

        /** The array type to be instantiated. */
        @Nullable public final ArrayType arrayType;

        /** The (mandatory) initializer for the array. */
        public final ArrayInitializer arrayInitializer;

        /** The resolved {@link #arrayType}. */
        @Nullable public final IClass arrayIClass;

        public
        NewInitializedArray(Location location, ArrayType arrayType, ArrayInitializer arrayInitializer) {
            super(location);
            this.arrayType        = arrayType;
            this.arrayInitializer = arrayInitializer;
            this.arrayIClass      = null;
        }

        NewInitializedArray(Location location, IClass arrayIClass, ArrayInitializer arrayInitializer) {
            super(location);
            this.arrayType        = null;
            this.arrayInitializer = arrayInitializer;
            this.arrayIClass      = arrayIClass;
        }

        // Implement "Atom".

        @Override public String
        toString() {
            return (
                "new "
                + (this.arrayType != null ? this.arrayType.toString() : String.valueOf(this.arrayIClass))
                + " { ... }"
            );
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitNewInitializedArray(this); }
    }

    /**
     * Representation of a JLS7 10.6 'array initializer'.
     * <p>
     * Allocates an array and initializes its members with (not necessarily
     * constant) values.
     */
    public static final
    class ArrayInitializer extends Located implements ArrayInitializerOrRvalue {

        /** The values to assign to the array elements. */
        public final ArrayInitializerOrRvalue[] values;

        public
        ArrayInitializer(Location location, ArrayInitializerOrRvalue[] values) {
            super(location);
            this.values = values;
        }

        @Override public String
        toString() { return " { (" + this.values.length + " values) }"; }
    }

    /** The union of {@link ArrayInitializer} and {@link Rvalue}. */
    public
    interface ArrayInitializerOrRvalue extends Locatable {
    }

    /** Abstract base class for the various Java&trade; literals; see JLS7 3.10. */
    public abstract static
    class Literal extends Rvalue {

        /** The text of the literal token, as in the source code. */
        public final String value;

        /** @param value The text of the literal token, as in the source code */
        public Literal(Location location, String value) { super(location); this.value = value; }

        // Implement "Atom".

        @Override public String
        toString() { return this.value; }
    }

    /** Representation of an "integer literal" (JLS7 3.10.1) (types {@code int} and {@code long}). */
    public static final
    class IntegerLiteral extends Literal {
        public IntegerLiteral(Location location, String value) { super(location, value); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitIntegerLiteral(this); }
    }

    /** Representation of a "floating-point literal" (JLS7 3.10.2) (types {@code float} and {@code double}). */
    public static final
    class FloatingPointLiteral extends Literal {
        public FloatingPointLiteral(Location location, String value) { super(location, value); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitFloatingPointLiteral(this); }
    }

    /** Representation of a "boolean literal" (JLS7 3.10.3) (type {@code boolean}). */
    public static final
    class BooleanLiteral extends Literal {
        public BooleanLiteral(Location location, String value) { super(location, value); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitBooleanLiteral(this); }
    }

    /** Representation of a "character literal" (JLS7 3.10.4) (type {@code char}). */
    public static final
    class CharacterLiteral extends Literal {
        public CharacterLiteral(Location location, String value) { super(location, value); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitCharacterLiteral(this); }
    }

    /** Representation of a "string literal" (JLS7 3.10.5) (type {@link String}). */
    public static final
    class StringLiteral extends Literal {
        public StringLiteral(Location location, String value) { super(location, value); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitStringLiteral(this); }
    }

    /** Representation of a "null literal" (JLS7 3.10.7). */
    public static final
    class NullLiteral extends Literal {
        public NullLiteral(Location location, String value) { super(location, value); }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitNullLiteral(this); }
    }

    /** This class is not used when code is parsed; it is intended for "programmatic" literals. */
    public static final
    class SimpleConstant extends Rvalue {

        /**
         * The value represented by this constant; either {@code null} (representing the {@code null} literal), a
         * {@link Byte}, {@link Short}, {@link Integer}, {@link Long}, {@link Float}, {@link Double}, {@link
         * Character}, {@link Boolean} or {@link String}.
         *
         * @see #SimpleConstant(Location)
         * @see #SimpleConstant(Location,byte)
         * @see #SimpleConstant(Location,short)
         * @see #SimpleConstant(Location,int)
         * @see #SimpleConstant(Location,long)
         * @see #SimpleConstant(Location,float)
         * @see #SimpleConstant(Location,double)
         * @see #SimpleConstant(Location,char)
         * @see #SimpleConstant(Location,boolean)
         * @see #SimpleConstant(Location,String)
         */
        @Nullable final Object value;

        /** Equivalent of the {@code null} literal. */
        public SimpleConstant(Location location) { super(location); this.value = null; }

        /** Equivalent of an literal, casted to {@code byte}. */
        public SimpleConstant(Location location, byte value) { super(location); this.value = value; }

        /** Equivalent of an literal, casted to {@code short}. */
        public SimpleConstant(Location location, short value) { super(location); this.value = value; }

        /** Equivalent of an {@link IntegerLiteral} with type {@code int}. */
        public SimpleConstant(Location location, int value) { super(location); this.value = value; }

        /** Equivalent of an {@link IntegerLiteral} with type {@code long}. */
        public SimpleConstant(Location location, long value) { super(location); this.value = value; }

        /**
         * Equivalent of a {@link FloatingPointLiteral} with type {@code float}.
         * Notice that this class supports the special values {@link Float#NaN}, {@link Float#NEGATIVE_INFINITY} and
         * {@link Float#POSITIVE_INFINITY}, which can not be represented with a {@link FloatingPointLiteral}.
         */
        public SimpleConstant(Location location, float value) { super(location); this.value = value; }

        /**
         * Equivalent of a {@link FloatingPointLiteral} with type {@code double}.
         * Notice that this class supports the special values {@link Double#NaN}, {@link Double#NEGATIVE_INFINITY} and
         * {@link Double#POSITIVE_INFINITY}, which can not be represented with a {@link FloatingPointLiteral}.
         */
        public SimpleConstant(Location location, double value) { super(location); this.value = value; }

        /** Equivalent of a {@link CharacterLiteral}. */
        public SimpleConstant(Location location, char value) { super(location); this.value = value; }

        /** Equivalent of a {@link BooleanLiteral}. */
        public SimpleConstant(Location location, boolean value) { super(location); this.value = value; }

        /**
         * Equivalent of a {@link StringLiteral}, or, if {@code value} is null, the equivalent of a {@link
         * NullLiteral}.
         */
        public SimpleConstant(Location location, String value) { super(location); this.value = value; }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(Visitor.RvalueVisitor<R, EX> visitor) throws EX { return visitor.visitSimpleConstant(this); }

        @Override public String
        toString() { return "[" + this.value + ']'; }
    }

    /**
     * All local variables have a slot number; local variables that get written into the 'localvariabletable'
     * also have a start and end offset that defines the variable's extent in the bytecode. If the name is null,
     * or variable debugging is not on, then the variable won't be written into the localvariabletable and the
     * offsets can be ignored.
     */
    public static
    class LocalVariableSlot {

        private short                  slotIndex = -1;
        @Nullable private String       name;
        @Nullable private final IClass type;
        @Nullable private Offset       start, end;

        public
        LocalVariableSlot(@Nullable String name, short slotNumber, @Nullable IClass type) {
            this.name      = name;
            this.slotIndex = slotNumber;
            this.type      = type;
        }

        @Override public String
        toString() {
            StringBuilder buf = new StringBuilder();

            buf.append("local var(").append(this.name);
            buf.append(", ").append(this.slotIndex);
            if (this.type != null) buf.append(", ").append(this.type);

            Offset s = this.start;
            if (s != null) buf.append(", ").append(s.offset);

            Offset e = this.end;
            if (e != null) buf.append(", ").append(e.offset);

            buf.append(")");

            return buf.toString();
        }

        /** @return The 'local variable index' associated with this local variable */
        public short getSlotIndex() { return this.slotIndex; }
        /** @param slotIndex The 'local variable index' to associate with this local variable */
        public void setSlotIndex(short slotIndex) { this.slotIndex = slotIndex; }

        /** @return The name of this local variable */
        @Nullable public String getName() { return this.name; }
        /** @param name The name of this local variable */
        public void setName(String name) { this.name = name; }

        /**
         * @return The {@link Offset} from which this local variable is visible
         */
        @Nullable public Offset
        getStart() { return this.start; }

        /**
         * @param start The {@link Offset} from which this local variable is visible
         */
        public void setStart(Offset start) { assert this.start == null; this.start = start; }

        /**
         * @return The {@link Offset} up to which this local variable is visible
         */
        @Nullable public Offset
        getEnd() { return this.end; }

        /** @param end The {@link Offset} up to which this local variable is visible */
        public void setEnd(Offset end) { assert this.end == null; this.end = end; }

        /** @return the resolved type of this local variable */
        public IClass getType() { assert this.type != null; return this.type; }
    }

    /** Representation of a local variable while it is in scope during compilation. */
    public static
    class LocalVariable {

        /** Whether this local variable has the FINAL modifier flag. */
        public final boolean finaL;

        /** The type of this local variable. */
        public final IClass type;

        /** The slot reserved for this local variable. */
        @Nullable public LocalVariableSlot slot;

        public
        LocalVariable(boolean finaL, IClass type) {
            this.finaL = finaL;
            this.type  = type;
        }

        @Override public String
        toString() {
            StringBuilder sb = new StringBuilder();

            if (this.finaL) sb.append("final ");
            sb.append(this.type).append(" ");

            return sb.toString();
        }

        /** @param slot The slot to reserve for this local variable */
        public void setSlot(LocalVariableSlot slot) { this.slot = slot; }

        /** @return The slot reserved for this local variable */
        public short
        getSlotIndex() {
            if (this.slot != null) return this.slot.getSlotIndex();
            return -1;
        }
    }

    /** Representation of a JLS7 4.5.1 'wildcard'. */
    public static
    class Wildcard implements TypeArgument {

        /**
         * Value for {@link #bounds} indicating that this wildcard has no bounds; {@link #referenceType} is irrelevant
         * in this case.
         */
        public static final int BOUNDS_NONE = 0;

        /** Value for {@link #bounds} indicating that this wildcard has 'extends' bounds. */
        public static final int BOUNDS_EXTENDS = 1;

        /** Value for {@link #bounds} indicating that this wildcard has 'super' bounds. */
        public static final int BOUNDS_SUPER = 2;

        /**
         * The kind of bounds that this wildcard has.
         *
         * @see #BOUNDS_NONE
         * @see #BOUNDS_EXTENDS
         * @see #BOUNDS_SUPER
         */
        public final int bounds;

        /** The reference type of this wildcard's EXTENDS or SUPER bounds. */
        @Nullable public final ReferenceType referenceType;

        public
        Wildcard() {
            this.bounds        = Wildcard.BOUNDS_NONE;
            this.referenceType = null;
        }

        public
        Wildcard(int bounds, ReferenceType referenceType) {
            assert bounds == Wildcard.BOUNDS_EXTENDS || bounds == Wildcard.BOUNDS_SUPER;
            this.bounds = bounds;
            assert referenceType != null;
            this.referenceType = referenceType;
        }

        @Override @Nullable public <R, EX extends Throwable> R
        accept(TypeArgumentVisitor<R, EX> visitor) throws EX { return visitor.visitWildcard(this); }

        @Override public String
        toString() {
            return (
                this.bounds == Wildcard.BOUNDS_EXTENDS ? "? extends " + this.referenceType :
                this.bounds == Wildcard.BOUNDS_SUPER   ? "? super "   + this.referenceType :
                "?"
            );
        }
    }

    /**
     * @return {@code null} iff {@code a == null}, or "" iff {@code a.length == 0}, or the elements of {@code a},
     *         converted to strings concatenated and separated with the {@code separator}
     */
    public static String
    join(Object[] a, String separator) {
        return Java.join(a, separator, 0, a.length);
    }

    /**
     * @return {@code ""} iff <var>off</var> {@code >=} <var>len</var>, or elements <var>off</var> ...
     *         <var>len</var>{@code -1} of <var>a</var>, converted to strings, concatenated and separated with the
     *         <var>separator</var>
     */
    public static String
    join(Object[] a, String separator, int off, int len) {
//        if (a == null) return ("(null)");
        if (off >= len) return "";
        StringBuilder sb = new StringBuilder(a[off].toString());
        for (++off; off < len; ++off) {
            sb.append(separator);
            sb.append(a[off]);
        }
        return sb.toString();
    }
}
