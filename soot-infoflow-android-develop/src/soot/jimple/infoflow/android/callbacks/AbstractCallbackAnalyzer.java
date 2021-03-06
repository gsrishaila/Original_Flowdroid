/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow.android.callbacks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.AnySubType;
import soot.Body;
import soot.Local;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.Stmt;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.callbacks.CallbackDefinition.CallbackType;
import soot.jimple.infoflow.android.callbacks.filters.ICallbackFilter;
import soot.jimple.infoflow.entryPointCreators.AndroidEntryPointConstants;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;
import soot.jimple.infoflow.util.SystemClassHandler;
import soot.jimple.toolkits.callgraph.Edge;
import soot.util.HashMultiMap;
import soot.util.MultiMap;

/**
 * Analyzes the classes in the APK file to find custom implementations of the
 * well-known Android callback and handler interfaces.
 * 
 * @author Steven Arzt
 *
 */
public abstract class AbstractCallbackAnalyzer {

	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	protected final SootClass scFragmentTransaction = Scene.v().getSootClassUnsafe("android.app.FragmentTransaction");
	protected final SootClass scFragment = Scene.v().getSootClassUnsafe(AndroidEntryPointConstants.FRAGMENTCLASS);
	
	protected final InfoflowAndroidConfiguration config;
	protected final Set<SootClass> entryPointClasses;
	protected final Set<String> androidCallbacks;
	
	protected final MultiMap<SootClass, CallbackDefinition> callbackMethods = new HashMultiMap<>();
	protected final MultiMap<SootClass, Integer> layoutClasses = new HashMultiMap<>();
	protected final Set<SootClass> dynamicManifestComponents = new HashSet<>();
	protected final MultiMap<SootClass, SootClass> fragmentClasses = new HashMultiMap<>();
	protected final Map<SootClass, Integer> fragmentIDs = new HashMap<>();
	
	protected final Set<ICallbackFilter> callbackFilters = new HashSet<ICallbackFilter>();
	protected final Set<SootClass> excludedEntryPoints = new HashSet<>();
	
	public AbstractCallbackAnalyzer(InfoflowAndroidConfiguration config,
			Set<SootClass> entryPointClasses) throws IOException {
		this(config, entryPointClasses, "AndroidCallbacks.txt");
	}
	
	public AbstractCallbackAnalyzer(InfoflowAndroidConfiguration config,
			Set<SootClass> entryPointClasses,
			String callbackFile) throws IOException {
		this(config, entryPointClasses, loadAndroidCallbacks(callbackFile));
	}

	public AbstractCallbackAnalyzer(InfoflowAndroidConfiguration config,
			Set<SootClass> entryPointClasses,
			Set<String> androidCallbacks) throws IOException {
		this.config = config;
		this.entryPointClasses = entryPointClasses;
		this.androidCallbacks = androidCallbacks;
	}
	
	/**
	 * Loads the set of interfaces that are used to implement Android callback
	 * handlers from a file on disk
	 * @param androidCallbackFile The file from which to load the callback definitions
	 * @return A set containing the names of the interfaces that are used to
	 * implement Android callback handlers
	 */
	private static Set<String> loadAndroidCallbacks(String androidCallbackFile) throws IOException {
		Set<String> androidCallbacks = new HashSet<String>();
		BufferedReader rdr = null;
		try {
			String fileName = androidCallbackFile;
			if (!new File(fileName).exists()) {
				fileName = "../soot-infoflow-android/AndroidCallbacks.txt";
				if (!new File(fileName).exists())
					throw new RuntimeException("Callback definition file not found");
			}
			rdr = new BufferedReader(new FileReader(fileName));
			String line;
			while ((line = rdr.readLine()) != null)
				if (!line.isEmpty())
					androidCallbacks.add(line);
		}
		finally {
			if (rdr != null)
				rdr.close();
		}
		return androidCallbacks;
	}

	/**
	 * Collects the callback methods for all Android default handlers
	 * implemented in the source code.
	 */
	public void collectCallbackMethods() {
		// Initialize the filters
		for (ICallbackFilter filter : callbackFilters)
			filter.reset();
	}
	
	/**
	 * Collects the callback methods that have been added since the last run.
	 * The semantics of this method depends on the concrete implementation. For
	 * non-incremental analyses, this method does nothing.
	 */
	public void collectCallbackMethodsIncremental() {
		//
	}
	
	/**
	 * Analyzes the given method and looks for callback registrations
	 * @param lifecycleElement The lifecycle element (activity, etc.) with which
	 * to associate the found callbacks
	 * @param method The method in which to look for callbacks
	 */
	protected void analyzeMethodForCallbackRegistrations(SootClass lifecycleElement, SootMethod method) {
		// Do not analyze system classes
		if (SystemClassHandler.isClassInSystemPackage(method.getDeclaringClass().getName()))
			return;
		if (!method.isConcrete())
			return;
		
		// Iterate over all statement and find callback registration methods
		Set<SootClass> callbackClasses = new HashSet<SootClass>();
		for (Unit u : method.retrieveActiveBody().getUnits()) {
			Stmt stmt = (Stmt) u;
			// Callback registrations are always instance invoke expressions
			if (stmt.containsInvokeExpr() && stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
				InstanceInvokeExpr iinv = (InstanceInvokeExpr) stmt.getInvokeExpr();
				
				String[] parameters = SootMethodRepresentationParser.v().getParameterTypesFromSubSignature(
						iinv.getMethodRef().getSubSignature().getString());
				for (int i = 0; i < parameters.length; i++) {
					String param = parameters[i];
					if (androidCallbacks.contains(param)) {
						Value arg = iinv.getArg(i);
						
						// This call must be to a system API in order to register an OS-level callback
						if (!SystemClassHandler.isClassInSystemPackage(iinv.getMethod().getDeclaringClass().getName()))
							continue;
						
						// We have a formal parameter type that corresponds to one of the Android
						// callback interfaces. Look for definitions of the parameter to estimate
						// the actual type.
						if (arg.getType() instanceof RefType && arg instanceof Local) {
							Set<Type> possibleTypes = Scene.v().getPointsToAnalysis().reachingObjects((Local) arg).possibleTypes();
							for (Type possibleType : possibleTypes) {
								RefType baseType;
								if (possibleType instanceof RefType)
									baseType = (RefType) possibleType;
								else if (possibleType instanceof AnySubType)
									baseType = ((AnySubType) possibleType).getBase();
								else {
									logger.warn("Unsupported type detected in callback analysis");
									continue;
								}
								
								SootClass targetClass = baseType.getSootClass();
								if (!SystemClassHandler.isClassInSystemPackage(targetClass.getName()))
									callbackClasses.add(targetClass);
							}
							
							// If we don't have pointsTo information, we take the type of the local
							if (possibleTypes.isEmpty()) {
								Type argType = ((Local) arg).getType();
								RefType baseType;
								if (argType instanceof RefType)
									baseType = (RefType) argType;
								else if (argType instanceof AnySubType)
									baseType = ((AnySubType) argType).getBase();
								else {
									logger.warn("Unsupported type detected in callback analysis");
									continue;
								}
								
								SootClass targetClass = baseType.getSootClass();
								if (!SystemClassHandler.isClassInSystemPackage(targetClass.getName()))
									callbackClasses.add(targetClass);
							}
						}
					}
				}
			}
		}
		
		// Analyze all found callback classes
		for (SootClass callbackClass : callbackClasses)
			analyzeClassInterfaceCallbacks(callbackClass, callbackClass, lifecycleElement);
	}
	
	/**
	 * Checks whether all filters accept the association between the callback class
	 * and its parent component
	 * @param lifecycleElement The hosting component's class
	 * @param targetClass The class implementing the callbacks
	 * @return True if all filters accept the given component-callback mapping, otherwise
	 * false
	 */
	private boolean filterAccepts(SootClass lifecycleElement, SootClass targetClass) {
		for (ICallbackFilter filter : callbackFilters)
			if (!filter.accepts(lifecycleElement, targetClass))
				return false;
		return true;
	}

	/**
	 * Checks whether all filters accept the association between the callback method
	 * and its parent component
	 * @param lifecycleElement The hosting component's class
	 * @param targetMethod The method implementing the callback
	 * @return True if all filters accept the given component-callback mapping, otherwise
	 * false
	 */
	private boolean filterAccepts(SootClass lifecycleElement, SootMethod targetMethod) {
		for (ICallbackFilter filter : callbackFilters)
			if (!filter.accepts(lifecycleElement, targetMethod))
				return false;
		return true;
	}

	/**
	 * Checks whether the given method dynamically registers a new broadcast
	 * receiver
	 * @param method The method to check
	 */
	protected void analyzeMethodForDynamicBroadcastReceiver(SootMethod method) {
		// Do not analyze system classes
		if (SystemClassHandler.isClassInSystemPackage(method.getDeclaringClass().getName()))
			return;
		if (!method.isConcrete() || !method.hasActiveBody())
			return;
		
		for (Unit u : method.getActiveBody().getUnits()) {
			Stmt stmt = (Stmt) u;
			if (stmt.containsInvokeExpr()) {
				if (stmt.getInvokeExpr().getMethod().getName().equals("registerReceiver")
						&& stmt.getInvokeExpr().getArgCount() > 0
						&& isInheritedMethod(stmt, "android.content.ContextWrapper",
								"android.content.Context")) {
					Value br = stmt.getInvokeExpr().getArg(0);
					if (br.getType() instanceof RefType) {
						RefType rt = (RefType) br.getType();
						dynamicManifestComponents.add(rt.getSootClass());
					}
				}
			}
		}
	}
	
	/**
	 * Checks whether the given method executes a fragment transaction 
	 * that creates new fragment
	 * 
	 * @author Goran Piskachev
	 * @param method The method to check
	 */
	protected void analyzeMethodForFragmentTransaction(SootClass lifecycleElement, SootMethod method) {
		if (scFragment == null || scFragmentTransaction == null)
			return;
		if (!method.isConcrete() || !method.hasActiveBody())
			return;
		
		// first check if there is a Fragment manager, a fragment transaction and a call to 
		// the add method which adds the fragment to the transaction
		boolean isFragmentManager = false;
		boolean isFragmentTransaction = false;
		boolean isAddTransaction = false;
		for (Unit u : method.getActiveBody().getUnits()) {
			Stmt stmt = (Stmt) u;
			if (stmt.containsInvokeExpr()) {
				if (stmt.getInvokeExpr().getMethod().getName().equals("getFragmentManager"))
					isFragmentManager = true;
				else if (stmt.getInvokeExpr().getMethod().getName().equals("beginTransaction"))
					isFragmentTransaction = true;
				else if (stmt.getInvokeExpr().getMethod().getName().equals("add"))
					isAddTransaction = true;
				else if (stmt.getInvokeExpr().getMethod().getName().equals("inflate")
						&& stmt.getInvokeExpr().getArgCount() > 1) {
					Value arg = stmt.getInvokeExpr().getArg(0);
					if (arg instanceof IntConstant)
						fragmentIDs.put(lifecycleElement, ((IntConstant) arg).value);
				}
			}
		}
		
		//now get the fragment class from the second argument of the add method from the transaction 
		if (isFragmentManager && isFragmentTransaction && isAddTransaction)
			for (Unit u : method.getActiveBody().getUnits()) {
				Stmt stmt = (Stmt) u;
				if (stmt.containsInvokeExpr()) {
					InvokeExpr invExpr = stmt.getInvokeExpr();
					if (invExpr instanceof InstanceInvokeExpr) {
						InstanceInvokeExpr iinvExpr = (InstanceInvokeExpr) invExpr;
						
						// Make sure that we referring to the correct class and method
						if (Scene.v().getFastHierarchy().canStoreType(iinvExpr.getBase().getType(), scFragmentTransaction.getType())
								&& stmt.getInvokeExpr().getMethod().getName().equals("add")) {
							// We take all fragments passed to the method
							for (int i = 0; i < stmt.getInvokeExpr().getArgCount(); i++) {
								Value br = stmt.getInvokeExpr().getArg(i);
								
								// Is this a fragment?
								if (br.getType() instanceof RefType) {
									RefType rt = (RefType) br.getType();
									if (Scene.v().getFastHierarchy().canStoreType(rt, scFragment.getType()))
										fragmentClasses.put(method.getDeclaringClass(), rt.getSootClass());
								}
							}
						}
					}
				}
			}
	}
	
	/**
	 * Gets whether the call in the given statement can end up in the respective method
	 * inherited from one of the given classes.
	 * @param stmt The statement containing the call sites
	 * @param classNames The base classes in which the call can potentially end up
	 * @return True if the given call can end up in a method inherited from one of
	 * the given classes, otherwise falae
	 */
	private boolean isInheritedMethod(Stmt stmt, String... classNames) {
		if (!stmt.containsInvokeExpr())
			return false;
		
		// Look at the direct callee
		SootMethod tgt = stmt.getInvokeExpr().getMethod();
		for (String className : classNames)
			if (className.equals(tgt.getDeclaringClass().getName()))
				return true;

		// If we have a callgraph, we can use that.
		if (Scene.v().hasCallGraph()) {
			Iterator<Edge> edgeIt = Scene.v().getCallGraph().edgesOutOf(stmt);
			while (edgeIt.hasNext()) {
				Edge edge = edgeIt.next();
				String targetClass = edge.getTgt().method().getDeclaringClass().getName();
				for (String className : classNames)
					if (className.equals(targetClass))
						return true;
			}
		}
		return false;
	}

	/**
	 * Checks whether this invocation calls Android's Activity.setContentView
	 * method
	 * @param inv The invocaton to check
	 * @return True if this invocation calls setContentView, otherwise false
	 */
	protected boolean invokesSetContentView(InvokeExpr inv) {
		String methodName = SootMethodRepresentationParser.v().getMethodNameFromSubSignature(
				inv.getMethodRef().getSubSignature().getString());
		if (!methodName.equals("setContentView"))
			return false;
		
		// In some cases, the bytecode points the invocation to the current
		// class even though it does not implement setContentView, instead
		// of using the superclass signature
		SootClass curClass = inv.getMethod().getDeclaringClass();
		while (curClass != null) {
			if (curClass.getName().equals("android.app.Activity")
					|| curClass.getName().equals("android.support.v7.app.ActionBarActivity"))
				return true;
			if (curClass.declaresMethod("void setContentView(int)"))
				return false;
			curClass = curClass.hasSuperclass() ? curClass.getSuperclass() : null;
		}
		return false;
	}
	
	/**
	 * Checks whether this invocation calls Android's LayoutInflater.inflate
	 * method
	 * @param inv The invocaton to check
	 * @return True if this invocation calls inflate, otherwise false
	 */
	protected boolean invokesInflate(InvokeExpr inv) {
		String methodName = SootMethodRepresentationParser.v().getMethodNameFromSubSignature(
				inv.getMethodRef().getSubSignature().getString());
		if (!methodName.equals("inflate"))
			return false;
		
		// In some cases, the bytecode points the invocation to the current
		// class even though it does not implement setContentView, instead
		// of using the superclass signature
		SootClass curClass = inv.getMethod().getDeclaringClass();
		while (curClass != null) {
			if (curClass.getName().equals("android.app.Fragment"))
				return true;
			if (curClass.declaresMethod("android.view.View inflate(int,android.view.ViewGroup,boolean)"))
				return false;
			curClass = curClass.hasSuperclass() ? curClass.getSuperclass() : null;
		}
		return false;
	}
	
	protected void analyzeMethodOverrideCallbacks(SootClass sootClass) {
		if (!sootClass.isConcrete())
			return;
		if (sootClass.isInterface())
			return;
		
		// Do not start the search in system classes
		if (config.getIgnoreFlowsInSystemPackages()
				&& SystemClassHandler.isClassInSystemPackage(sootClass.getName()))
			return;
		
		// There are also some classes that implement interesting callback methods.
		// We model this as follows: Whenever the user overwrites a method in an
		// Android OS class, we treat it as a potential callback.
		Set<String> systemMethods = new HashSet<String>(10000);
		for (SootClass parentClass : Scene.v().getActiveHierarchy().getSuperclassesOf(sootClass)) {
			if (SystemClassHandler.isClassInSystemPackage(parentClass.getName()))
				for (SootMethod sm : parentClass.getMethods())
					if (!sm.isConstructor())
						systemMethods.add(sm.getSubSignature());
		}
		
		// Iterate over all user-implemented methods. If they are inherited
		// from a system class, they are callback candidates.
		for (SootClass parentClass : Scene.v().getActiveHierarchy().getSubclassesOfIncluding(sootClass)) {
			if (SystemClassHandler.isClassInSystemPackage(parentClass.getName()))
				continue;
			for (SootMethod method : parentClass.getMethods()) {
				if (!systemMethods.contains(method.getSubSignature()))
					continue;

				// This is a real callback method
				checkAndAddMethod(method, sootClass, CallbackType.Default);
			}
		}
	}
	
	private SootMethod getMethodFromHierarchyEx(SootClass c, String methodSignature) {
		if (c.declaresMethod(methodSignature))
			return c.getMethod(methodSignature);
		if (c.hasSuperclass())
			return getMethodFromHierarchyEx(c.getSuperclass(), methodSignature);
		throw new RuntimeException("Could not find method");
	}

	private void analyzeClassInterfaceCallbacks(SootClass baseClass, SootClass sootClass,
			SootClass lifecycleElement) {
		// We cannot create instances of abstract classes anyway, so there is no
		// reason to look for interface implementations
		if (!baseClass.isConcrete())
			return;
		
		// Do not analyze system classes
		if (SystemClassHandler.isClassInSystemPackage(baseClass.getName()))
			return;
		if (SystemClassHandler.isClassInSystemPackage(sootClass.getName()))
			return;
		
		// Check the filters
		if (!filterAccepts(lifecycleElement, baseClass))
			return;
		if (!filterAccepts(lifecycleElement, sootClass))
			return;
		
		// If we are a class, one of our superclasses might implement an Android
		// interface
		if (sootClass.hasSuperclass())
			analyzeClassInterfaceCallbacks(baseClass, sootClass.getSuperclass(), lifecycleElement);
		
		// Do we implement one of the well-known interfaces?
		for (SootClass i : collectAllInterfaces(sootClass)) {
			if (androidCallbacks.contains(i.getName())) {
				CallbackType callbackType = isUICallback(i) ? CallbackType.Widget
						: CallbackType.Default;
				
				for (SootMethod sm : i.getMethods())
					checkAndAddMethod(getMethodFromHierarchyEx(baseClass,
							sm.getSubSignature()), lifecycleElement, callbackType);
			}
		}
	}

	/**
	 * Gets whether the given callback interface or class represents a UI callback
	 * @param i The callback interface or class to check
	 * @return True if the given callback interface or class represents a UI callback,
	 * otherwise false
	 */
	private boolean isUICallback(SootClass i) {
		return i.getName().startsWith("android.widget")
				|| i.getName().startsWith("android.view")
				|| i.getName().startsWith("android.content.DialogInterface$");
	}

	/**
	 * Checks whether the given Soot method comes from a system class. If not,
	 * it is added to the list of callback methods.
	 * @param method The method to check and add
	 * @param baseClass The base class (activity, service, etc.) to which this
	 * callback method belongs
	 * @param callbackType The type of callback to be registered
	 * @return True if the method is new, i.e., has not been seen before, otherwise
	 * false
	 */
	protected boolean checkAndAddMethod(SootMethod method, SootClass baseClass,
			CallbackType callbackType) {
		// Do not call system methods
		if (SystemClassHandler.isClassInSystemPackage(method.getDeclaringClass().getName()))
			return false;

		// Skip empty methods
		if (method.isConcrete() && isEmpty(method.retrieveActiveBody()))
			return false;
		
		// Skip constructors
		if (method.isConstructor() || method.isStaticInitializer())
			return false;
		
		// Check the filters
		if (!filterAccepts(baseClass, method.getDeclaringClass()))
			return false;
		if (!filterAccepts(baseClass, method))
			return false;
		
		return this.callbackMethods.put(baseClass, new CallbackDefinition(
				method, callbackType));
	}

	private boolean isEmpty(Body activeBody) {
		for (Unit u : activeBody.getUnits())
			if (!(u instanceof IdentityStmt || u instanceof ReturnVoidStmt))
				return false;
		return true;
	}

	private Set<SootClass> collectAllInterfaces(SootClass sootClass) {
		Set<SootClass> interfaces = new HashSet<SootClass>(sootClass.getInterfaces());
		for (SootClass i : sootClass.getInterfaces())
			interfaces.addAll(collectAllInterfaces(i));
		return interfaces;
	}
	
	public MultiMap<SootClass, CallbackDefinition> getCallbackMethods() {
		return this.callbackMethods;
	}
	
	public MultiMap<SootClass, Integer> getLayoutClasses() {
		return this.layoutClasses;
	}
	
	public MultiMap<SootClass, SootClass> getFragmentClasses() {
		return this.fragmentClasses;
	}
	
	public Set<SootClass> getDynamicManifestComponents() {
		return this.dynamicManifestComponents;
	}
	
	/**
	 * Adds a new filter that checks every callback before it is associated with
	 * the respective host component
	 * @param filter The filter to add
	 */
	public void addCallbackFilter(ICallbackFilter filter) {
		this.callbackFilters.add(filter);
	}
	
	/**
	 * Excludes an entry point from all further processing. No more callbacks
	 * will be collected for the given entry point
	 * @param entryPoint The entry point to exclude
	 */
	public void excludeEntryPoint(SootClass entryPoint) {
		this.excludedEntryPoints.add(entryPoint);
	}
	
	/**
	 * Checks whether the given class is an excluded entry point
	 * @param entryPoint The entry point to check
	 * @return True if the given class is an excluded entry point, otherwise false
	 */
	public boolean isExcludedEntryPoint(SootClass entryPoint) {
		return this.excludedEntryPoints.contains(entryPoint);
	}
	
}
