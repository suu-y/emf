/**
 * Copyright (c) 2006-2012 IBM Corporation and others
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 *   IBM - Initial API and implementation
 */
package org.eclipse.emf.ecore.xcore.exporter;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.codegen.ecore.genmodel.GenAnnotation;
import org.eclipse.emf.codegen.ecore.genmodel.GenBase;
import org.eclipse.emf.codegen.ecore.genmodel.GenClassifier;
import org.eclipse.emf.codegen.ecore.genmodel.GenFeature;
import org.eclipse.emf.codegen.ecore.genmodel.GenModel;
import org.eclipse.emf.codegen.ecore.genmodel.GenModelFactory;
import org.eclipse.emf.codegen.ecore.genmodel.GenModelPackage;
import org.eclipse.emf.codegen.ecore.genmodel.GenPackage;
import org.eclipse.emf.codegen.util.ImportManager;
import org.eclipse.emf.common.util.BasicDiagnostic;
import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.Monitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EGenericType;
import org.eclipse.emf.ecore.EModelElement;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EPackage.Registry;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.plugin.EcorePlugin;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xcore.XGenericType;
import org.eclipse.emf.ecore.xcore.XImportDirective;
import org.eclipse.emf.ecore.xcore.XPackage;
import org.eclipse.emf.ecore.xcore.XReference;
import org.eclipse.emf.ecore.xcore.XcoreFactory;
import org.eclipse.emf.ecore.xcore.XcorePackage;
import org.eclipse.emf.ecore.xcore.mappings.XcoreMapper;
import org.eclipse.emf.ecore.xcore.scoping.XcoreImportedNamespaceAwareScopeProvider;
import org.eclipse.emf.ecore.xcore.ui.quickfix.XcoreClasspathUpdater;
import org.eclipse.emf.ecore.xcore.util.EcoreXcoreBuilder;
import org.eclipse.emf.ecore.xcore.util.XcoreGenModelBuilder;
import org.eclipse.emf.ecore.xcore.util.XcoreGenModelInitializer;
import org.eclipse.emf.exporter.ModelExporter;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.xtext.formatting2.FormatterPreferenceValuesProvider;
import org.eclipse.xtext.formatting2.FormatterRequest;
import org.eclipse.xtext.formatting2.IFormatter2;
import org.eclipse.xtext.formatting2.regionaccess.ITextReplacement;
import org.eclipse.xtext.formatting2.regionaccess.internal.NodeModelBasedRegionAccessBuilder;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.naming.IQualifiedNameProvider;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.preferences.IPreferenceValues;
import org.eclipse.xtext.preferences.TypedPreferenceValues;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.SaveOptions;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.XtextProjectHelper;
import org.eclipse.xtext.util.RuntimeIOException;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Provider;

public class XcoreExporter extends ModelExporter
{
  private static String SAVE_FAMILY = "Save";
  
  public static Diagnostic RETRY = new BasicDiagnostic();

  @Inject
  Provider<EcoreXcoreBuilder> ecoreXcoreBuilderProvider;

  @Inject
  XcoreGenModelBuilder genModelBuilder;

  @Inject
  XcoreGenModelInitializer genModelInitializer;

  @Inject
  XcoreMapper mapper;

  @Inject
  private IScopeProvider scopeProvider;

  @Inject
  private IQualifiedNameProvider qualifiedNameProvider;

  @Inject
  private IQualifiedNameConverter qualifiedNameConverter;

  @Inject
  protected IFormatter2 formatter;

  @Inject
  private FormatterPreferenceValuesProvider preferencesProvider;

  @Inject
  private Provider<ResourceSet> resourceSetProvider;

  private static final Set<String> IMPLICIT_ALIASES = Sets.newHashSet();
  static
  {
    for (EDataType eDataType : XcoreImportedNamespaceAwareScopeProvider.IMPLICIT_ALIASES)
    {
      IMPLICIT_ALIASES.add("org.eclipse.emf.ecore." + eDataType.getName());
    }
  }

  @Override
  public String getID()
  {
    return "org.eclipse.emf.ecore.xcore.exporter";
  }

  @Override
  public void dispose()
  {
    super.dispose();
  }

  @Override
  protected String getDefaultArtifactLocation(EPackage ePackage)
  {
    return getEPackageToGenPackageMap().get(ePackage).getPrefix() + ".xcore";
  }

  @Override
  protected String doCheckEPackageArtifactLocation(String location, String packageName)
  {
    if (!location.endsWith(".xcore"))
    {
      return XcoreExporterPlugin.INSTANCE.getString("_UI_InvalidArtifactFileNameExtension_message");
    }
    return super.doCheckEPackageArtifactLocation(location, packageName);
  }

  @Override
  protected Diagnostic doExport(Monitor monitor, ModelExporter.ExportData exportData) throws Exception
  {
    // Add natures and libraries if necessary.
    // Because the builder needs to run to update the index, if we do add a library, we need to return early to allow the workspace modify operation to complete.
    // After that's done, we want to retry, at which point there should be no libraries added the second time.
    //
    boolean retry = false;
    for (URI xcoreLocationURI : exportData.genPackageToArtifactURI.values())
    {
      // Add the Xtext nature if it's absent.
      //
      IFile xcoreFile = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(xcoreLocationURI.toPlatformString(true)));
      final IProject xcoreProject = xcoreFile.getProject();
      if (xcoreProject.isAccessible())
      {
        if (!xcoreProject.hasNature(XtextProjectHelper.NATURE_ID))
        {
          IProjectDescription description = xcoreProject.getDescription();
          String[] natures = description.getNatureIds();
          String[] newNatures = new String[natures.length + 1];
          System.arraycopy(natures, 0, newNatures, 0, natures.length);
          newNatures[natures.length] = XtextProjectHelper.NATURE_ID;
          description.setNatureIds(newNatures);
          xcoreProject.setDescription(description, null);
        }
        XcoreClasspathUpdater xcoreClasspathUpdater = new XcoreClasspathUpdater();
        IJavaProject xcoreJavaProject = JavaCore.create(xcoreProject);
        retry = xcoreClasspathUpdater.addBundle(xcoreJavaProject, "org.eclipse.emf.ecore.xcore.lib", null) || retry;
        retry = xcoreClasspathUpdater.addBundle(xcoreJavaProject, "org.eclipse.xtext.xbase.lib", null) || retry;
      }
    }
    if (retry)
    {
      return RETRY;
    }

    final List<Runnable> runnables = new ArrayList<Runnable>();
    for (Map.Entry<GenPackage, URI> entry : exportData.genPackageToArtifactURI.entrySet())
    {
      GenPackage genPackage = entry.getKey();
      URI xcoreLocationURI = entry.getValue();

      // Create an appropriate resource set for Xcore models.
      //
      final ResourceSet resourceSet = resourceSetProvider.get();
      final Map<URI, URI> uriMap = resourceSet.getURIConverter().getURIMap();
      Registry packageRegistry = resourceSet.getPackageRegistry();
      uriMap.putAll(EcorePlugin.computePlatformURIMap(true));

      // Load a clone of the GenModel in the new resource set.
      //
      GenModel inputGenModel = (GenModel)resourceSet.getEObject(EcoreUtil.getURI(getGenModel()), true);
      inputGenModel.reconcile();

      Resource inputResource = inputGenModel.eResource();
      final XtextResource outputResource = (XtextResource)resourceSet.createResource(xcoreLocationURI);

      // Create a fresh new GenModel for the packages we just loaded.
      //
      GenModel genModel = GenModelFactory.eINSTANCE.createGenModel();
      List<EPackage> ePackageClones = new ArrayList<EPackage>();
      for (GenPackage inputGenPackage : inputGenModel.getGenPackages())
      {
        ePackageClones.add(inputGenPackage.getEcorePackage());
      }
      genModel.initialize(ePackageClones);

      // Add it to the resource and initialize it like we do any other new GenModel
      //
      inputResource.getContents().add(genModel);
      genModelInitializer.initialize(genModel, true);

      // Walk the two GenModels in lockstep and compare settings.
      // We need to know which ones should be converted to annotations and which would be redundant because they represent defaults.
      //
      final GenPackage inputGenPackage = (GenPackage)resourceSet.getEObject(EcoreUtil.getURI(genPackage), true);
      new Object()
      {
        void visit(GenBase genBase1, GenBase genBase2)
        {
          if (genBase1.eClass() == genBase2.eClass())
          {
            System.out.println("SATD ID: 38");
            //
            for (EAttribute eAttribute : genBase1.eClass().getEAllAttributes())
            {
              if (!eAttribute.isMany())
              {
                Object value1 = genBase1.eGet(eAttribute);
                Object value2 = genBase2.eGet(eAttribute);
                if (value1 == null ? value2 != null : !value1.equals(value2))
                {
                  // For the case of the GenModel, we record its annotations on the package.
                  //
                  EModelElement eModelElement = genBase2.getEcoreModelElement();
                  if (eModelElement == null && genBase2 instanceof GenModel)
                  {
                    eModelElement = inputGenPackage.getEcorePackage();
                  }
                  EcoreUtil.setAnnotation(eModelElement, GenModelPackage.eNS_URI, eAttribute.getName(), EcoreUtil.convertToString(eAttribute.getEAttributeType(), value1));
                }
              }
            }

            for (Iterator<GenBase> i = getContents(genBase1).iterator(), j = getContents(genBase2).iterator(); i.hasNext() && j.hasNext();)
            {
              GenBase content1 = i.next();
              GenBase content2 = j.next();
              visit((GenBase)content1, (GenBase)content2);
            }
          }
        }

        List<GenBase> getContents(EObject eObject)
        {
          List<GenBase> result = new ArrayList<GenBase>();
          for (EObject eContent : eObject.eContents())
          {
            if (eContent instanceof GenBase && !(eContent instanceof GenAnnotation))
            {
              result.add((GenBase)eContent);
            }
          }
          return result;
        }
      }.visit(inputGenModel, genModel);

      // Use the builder to create an appropriate Xcore instance for populating the output resource.
      //
      EcoreXcoreBuilder ecoreXcoreBuilder = ecoreXcoreBuilderProvider.get();
      ecoreXcoreBuilder.initialize(inputGenModel);
      XPackage xPackage = ecoreXcoreBuilder.getXPackage(inputGenPackage.getEcorePackage());
      outputResource.getContents().add(xPackage);
      outputResource.getContents().add(inputGenModel);
      outputResource.getContents().add(inputGenPackage.getEcorePackage());

      // Put all the specialize internal GenPackages in appropriately named resources so the serializer can resolve them.
      // Xtext complains about duplicate resources in the resource set if we put them in the original resource set.
      //
      ResourceSet additionalResources = resourceSetProvider.get();
      GenPackage ecoreGenPackage = inputGenModel.getEcoreGenPackage();
      if (ecoreGenPackage != null)
      {
        URI ecoreResourceURI = URI.createPlatformResourceURI("/org.eclipse.emf.ecore/model/Ecore.genmodel", false);
        packageRegistry.put(ecoreResourceURI.toString(), ecoreGenPackage.getEcorePackage());
        Resource ecoreResource = additionalResources.createResource(ecoreResourceURI);
        ecoreResource.getContents().add(ecoreGenPackage.getGenModel());
      }
      GenPackage xmlTypeGenPackage = inputGenModel.getXMLTypeGenPackage();
      if (xmlTypeGenPackage != null)
      {
        URI xmlTypeResourceURI = URI.createPlatformResourceURI("/org.eclipse.emf.ecore/model/XMLType.genmodel", false);
        packageRegistry.put(xmlTypeResourceURI.toString(), xmlTypeGenPackage.getEcorePackage());
        Resource xmlTypeResource = additionalResources.createResource(xmlTypeResourceURI);
        xmlTypeResource.getContents().add(xmlTypeGenPackage.getGenModel());
      }
      GenPackage xmlNamespaceGenPackage = inputGenModel.getXMLNamespaceGenPackage();
      if (xmlNamespaceGenPackage != null)
      {
        URI xmlNamespaceResourceURI = URI.createPlatformResourceURI("/org.eclipse.emf.ecore/model/XMLNamespace.genmodel", false);
        packageRegistry.put(xmlNamespaceResourceURI.toString(), xmlNamespaceGenPackage.getEcorePackage());
        Resource xmlNamespaceResource = additionalResources.createResource(xmlNamespaceResourceURI);
        xmlNamespaceResource.getContents().add(xmlNamespaceGenPackage.getGenModel());
      }

      // Do the final linking step and build the map.
      //
      ecoreXcoreBuilder.link();
      genModelBuilder.buildMap(inputGenModel);

      // Use an import manager to create imports for all the things we reference in the Xcore instance.
      //
      ImportManager importManager =
        new ImportManager(inputGenPackage.getInterfacePackageName())
        {
          @Override
          protected boolean shouldImport(String packageName, String shortName, String importName)
          {
            return true;
          }
        };
      Map<EGenericType, XGenericType> genericTypeMap = ecoreXcoreBuilder.getGenericTypeMap();
      for (TreeIterator<EObject> i = inputGenPackage.getEcorePackage().eAllContents(); i.hasNext();)
      {
        EObject eObject = i.next();
        if (eObject instanceof EGenericType)
        {
          EGenericType eGenericType = (EGenericType)eObject;
          EClassifier eClassifier = eGenericType.getEClassifier();
          if (eClassifier != null)
          {
            GenClassifier genClassifier = inputGenModel.findGenClassifier(eClassifier);
            QualifiedName qualifiedName = qualifiedNameProvider.getFullyQualifiedName(genClassifier);
            String qualifiedNameValue = qualifiedNameConverter.toString(qualifiedName);
            if (!IMPLICIT_ALIASES.contains(qualifiedNameValue))
            {
              importManager.addImport(qualifiedNameValue);
            }

            if (genClassifier.eResource() != outputResource)
            {
              // We need to ensure that if we resolve to a different instance, we switch to use that so that serialization will find the right instance.
              //
              XGenericType xGenericType = genericTypeMap.get(eGenericType);
              IScope scope = scopeProvider.getScope(xGenericType, XcorePackage.Literals.XGENERIC_TYPE__TYPE);
              IEObjectDescription genClassifierDescription = scope.getSingleElement(qualifiedName);
              if (genClassifierDescription != null)
              {
                EObject resolvedGenClassifier = resourceSet.getEObject(genClassifierDescription.getEObjectURI(), true);
                if (resolvedGenClassifier != null && resolvedGenClassifier != genClassifier)
                {
                  xGenericType.setType((GenClassifier)resolvedGenClassifier);
                }
              }
            }
          }
        }
        else if (eObject instanceof EReference)
        {
          EReference eReference = (EReference)eObject;
          XReference xReference = (XReference)mapper.getToXcoreMapping(eReference).getXcoreElement();
          EList<GenFeature> keys = xReference.getKeys();
          GenFeature opposite = xReference.getOpposite();
          if (opposite != null && opposite.eResource() != outputResource)
          {
            IScope scope = scopeProvider.getScope(xReference, XcorePackage.Literals.XREFERENCE__OPPOSITE);
            IEObjectDescription genFeatureDescription = scope.getSingleElement(QualifiedName.create(opposite.getName()));
            if (genFeatureDescription != null)
            {
              EObject resolvedGenFeature = resourceSet.getEObject(genFeatureDescription.getEObjectURI(), true);
              if (resolvedGenFeature != null)
              {
                xReference.setOpposite((GenFeature)resolvedGenFeature);
              }
            }
          }
          if (!keys.isEmpty())
          {
            IScope scope = scopeProvider.getScope(xReference, XcorePackage.Literals.XREFERENCE__KEYS);
            for (ListIterator<GenFeature> k = keys.listIterator(); k.hasNext(); )
            {
              GenFeature key = k.next();
              if (key.eResource() != outputResource)
              {
                IEObjectDescription genFeatureDescription = scope.getSingleElement(QualifiedName.create(key.getName()));
                if (genFeatureDescription != null)
                {
                  EObject resolvedGenFeature = resourceSet.getEObject(genFeatureDescription.getEObjectURI(), true);
                  if (resolvedGenFeature != null)
                  {
                    k.set((GenFeature)resolvedGenFeature);
                  }
                }
              }
            }
          }
        }
        else if (eObject instanceof EPackage)
        {
          i.prune();
        }
      }

      // Convert the needed imports to import directives.
      //
      for (String qualifiedName : importManager.getImports())
      {
        XImportDirective xImportDirective = XcoreFactory.eINSTANCE.createXImportDirective();
        xImportDirective.setImportedNamespace(qualifiedName);
        xPackage.getImportDirectives().add(xImportDirective);
      }

      // Save the final result.
      //
      final Map<Object, Object> options = new HashMap<Object, Object>();
      SaveOptions.newBuilder().format().getOptions().addTo(options);

      Runnable runnable =
        new Runnable()
        {
          @Override
          public void run()
          {
            try
            {
              // Temporarily clear the mappings so that the serializer properly finds the non-normalized URIs in the index.
              //
              Map<URI, URI> copyiedURIMap = new HashMap<URI, URI>(uriMap);
              uriMap.clear();
              outputResource.save(options);
              uriMap.putAll(copyiedURIMap);

              outputResource.unload();
              outputResource.load(null);
              IParseResult parseResult = outputResource.getParseResult();
              if (parseResult != null)
              {
                IPreferenceValues configuration = preferencesProvider.getPreferenceValues(outputResource);
                try
                {
                  FormatterRequest request = new FormatterRequest();
                  request.setTextRegionAccess(new NodeModelBasedRegionAccessBuilder().withResource(outputResource).create());
                  request.setPreferences(TypedPreferenceValues.castOrWrap(configuration));
                  request.addRegion(parseResult.getRootNode().getTotalTextRegion());
                  List<ITextReplacement> edits = formatter.format(request);

                  BufferedInputStream bufferedInputStream = new BufferedInputStream(resourceSet.getURIConverter().createInputStream(outputResource.getURI()));
                  byte[] input = new byte[bufferedInputStream.available()];
                  bufferedInputStream.read(input);
                  bufferedInputStream.close();
                  String text = new String(input, outputResource.getEncoding());
                  StringBuilder builder = new StringBuilder(text);
                  for (int i = edits.size(); --i >= 0; )
                  {
                    ITextReplacement replacement = edits.get(i);
                    int offset = replacement.getOffset();
                    builder.replace(offset, offset + replacement.getLength(), replacement.getReplacementText());
                  }

                  OutputStream outputStream = resourceSet.getURIConverter().createOutputStream(outputResource.getURI());
                  outputStream.write(builder.toString().getBytes(outputResource.getEncoding()));
                  outputStream.close();
                }
                catch (Throwable throwable)
                {
                  XcoreExporterPlugin.INSTANCE.log(throwable);
                }
              }
            }
            catch (IOException exception)
            {
              throw new RuntimeIOException(exception);
            }
          }
        };
      runnables.add(runnable);
    }

    // Do this in a job so that Xtext nature we added has a chance to build the index needed by the serializer.
    //
    Job job =
      new Job("Save")
      {
        @Override
        public boolean belongsTo(Object family)
        {
          return family == SAVE_FAMILY;
        }
        
        @Override
        protected IStatus run(IProgressMonitor monitor)
        {
          try
          {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IWorkspaceRunnable runnable =
               new IWorkspaceRunnable()
              {
                @Override
                public void run(IProgressMonitor monitor) throws CoreException
                {
                  for (Runnable runnable : runnables)
                  {
                    runnable.run();
                  }
                }
              };
            workspace.run(runnable, null, IWorkspace.AVOID_UPDATE, null);
            return Status.OK_STATUS;
          }
          catch (Exception exception)
          {
            return Status.CANCEL_STATUS;
          }
        }
      };
    job.schedule();

    return Diagnostic.OK_INSTANCE;
  }
  
  public void waitForSaveJob()
  {
    try
    {
      Job.getJobManager().join(SAVE_FAMILY, new NullProgressMonitor());
    }
    catch (Throwable e)
    {
      // Ignore
    }
  }

  protected URI getReferencedGenPackageArtifactURI(ModelExporter.ExportData exportData, GenPackage genPackage)
  {
    URI artifactURI = exportData.referencedGenPackagesToArtifactURI.get(genPackage);
    if (artifactURI == null)
    {
      artifactURI = exportData.genPackageToArtifactURI.get(genPackage);
      if (artifactURI == null)
      {
        for (Map.Entry<GenPackage, URI> entry : exportData.referencedGenPackagesToArtifactURI.entrySet())
        {
          GenPackage referencedGenPackage = entry.getKey();
          if (genPackage.getNSURI().equals(referencedGenPackage.getNSURI()) &&
                genPackage.getEcorePackage().getName().equals(referencedGenPackage.getEcorePackage().getName()))
          {
            artifactURI = entry.getValue();
          }
        }
      }
    }
    return artifactURI;
  }

  @Override
  protected void adjustGenModel()
  {
    // Ignore all packages not actually contained in the GenModel.
    // We don't need information about where referenced GenPackages are located.
    //
    GenModel genModel = getGenModel();
    for (ListIterator<EPackage> i = getEPackages().listIterator(); i.hasNext();)
    {
      if (!EcoreUtil.isAncestor(genModel, getEPackageToGenPackageMap().get(i.next())))
      {
        i.remove();
      }
    }
    super.adjustGenModel();
  }
}
