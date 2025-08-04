/**
 * Copyright (c) 2011-2012 Eclipse contributors and others.
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 */
package org.eclipse.emf.ecore.xcore.ui;


import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.codegen.ecore.ui.EmptyProjectWizard;
import org.eclipse.emf.codegen.util.CodeGenUtil;
import org.eclipse.emf.ecore.xcore.ui.internal.XcoreActivator;
import org.eclipse.emf.edit.ui.provider.ExtendedImageRegistry;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.xtext.ui.XtextProjectHelper;


public class EmptyXcoreProjectWizard extends EmptyProjectWizard
{
  @Override
  public void init(IWorkbench workbench, IStructuredSelection selection)
  {
    this.workbench = workbench;
    this.selection = selection;
    setDefaultPageImageDescriptor(ExtendedImageRegistry.INSTANCE.getImageDescriptor(XcoreActivator.getInstance().getBundle().getEntry("icons/full/wizban/NewXcoreProject.gif")));
    setWindowTitle(Platform.getResourceBundle(XcoreActivator.getInstance().getBundle()).getString("_UI_NewXcoreProject_title"));
  }

  @Override
  public void addPages()
  {
    System.out.println("SATD ID: 40");
    super.addPages();
    IWizardPage[] pages = getPages();
    IWizardPage page = pages[pages.length - 1];
    page.setTitle(Platform.getResourceBundle(XcoreActivator.getInstance().getBundle()).getString("_UI_NewXcoreProject_title"));
    page.setDescription(Platform.getResourceBundle(XcoreActivator.getInstance().getBundle()).getString("_UI_NewXcoreProject_description"));
  }

  @Override
  public void modifyWorkspace(IProgressMonitor progressMonitor) throws CoreException, UnsupportedEncodingException, IOException
  {
    super.modifyWorkspace(progressMonitor);
    IProjectDescription projectDescription = project.getDescription();
    String[] natureIds = projectDescription.getNatureIds();
    String[] newNatureIds = new String [natureIds.length + 1];
    System.arraycopy(natureIds, 0, newNatureIds, 0, natureIds.length);
    newNatureIds[natureIds.length] = XtextProjectHelper.NATURE_ID;
    projectDescription.setNatureIds(newNatureIds);
    project.setDescription(projectDescription, progressMonitor);

    IJavaProject javaProject = JavaCore.create(project);
    IClasspathEntry[] classpath = javaProject.getRawClasspath();
    IClasspathEntry[] newClasspath = new IClasspathEntry [classpath.length + 1];
    for (int i = 0, index = 0, length = newClasspath.length; index < length; ++i, ++index)
    {
      newClasspath[index] = classpath[i];
      if (classpath[i].getEntryKind() == IClasspathEntry.CPE_SOURCE)
      {
        IPath path = classpath[i].getPath();
        IPath srcGenPath = path.removeLastSegments(1).append(path.lastSegment() + "-gen");
        IClasspathEntry srcGen = JavaCore.newSourceEntry(srcGenPath);
        CodeGenUtil.EclipseUtil.findOrCreateContainer(srcGenPath, true, genModelProjectLocation, progressMonitor);
        newClasspath[++index] = srcGen;
      }
    }
    javaProject.setRawClasspath(newClasspath, progressMonitor);
  }

  @Override
  protected String[] getRequiredBundles()
  {
    return new String []{ "org.eclipse.emf.ecore", "org.eclipse.xtext.xbase.lib", "org.eclipse.emf.ecore.xcore.lib" };
  }

}
