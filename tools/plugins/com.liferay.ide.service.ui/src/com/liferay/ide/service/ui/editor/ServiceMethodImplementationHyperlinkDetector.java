/*******************************************************************************
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 *******************************************************************************/

package com.liferay.ide.service.ui.editor;

import com.liferay.ide.core.util.CoreUtil;
import com.liferay.ide.service.core.util.ServiceUtil;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICodeAssist;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.ui.actions.SelectionConverter;
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;
import org.eclipse.jdt.internal.ui.javaeditor.JavaEditor;
import org.eclipse.jdt.internal.ui.text.JavaWordFinder;
import org.eclipse.jdt.ui.actions.SelectionDispatchAction;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * @author Gregory Amerson
 *
 */
@SuppressWarnings( "restriction" )
public class ServiceMethodImplementationHyperlinkDetector extends AbstractHyperlinkDetector
{
    private IJavaElement[] lastElements;
    private ITypeRoot lastInput;
    private long lastModStamp;
    private IRegion lastWordRegion;

    private void addHyperlinks(
        final List<IHyperlink> links, final IRegion wordRegion, final SelectionDispatchAction openAction,
        final IMethod method, final boolean qualify, final JavaEditor editor )
    {
        if( shouldAddServiceHyperlink( editor ) )
        {
            final IMethod implMethod = getServiceImplMethod( method );

            if( implMethod != null )
            {
                links.add( new ServiceMethodImplementationHyperlink( wordRegion, openAction, implMethod, qualify ) );
            }
        }
    }

    public IHyperlink[] detectHyperlinks( ITextViewer textViewer, IRegion region, boolean canShowMultipleHyperlinks )
    {
        IHyperlink[] retval = null;

        final ITextEditor textEditor = (ITextEditor) getAdapter( ITextEditor.class );
        final ITypeRoot input = EditorUtility.getEditorInputJavaElement( textEditor, false );
        final IAction openAction = textEditor.getAction( "OpenEditor" );

        if( shouldDetectHyperlinks( textEditor, input, openAction, region ) )
        {
            final IDocumentProvider documentProvider = textEditor.getDocumentProvider();
            final IEditorInput editorInput = textEditor.getEditorInput();
            final IDocument document = documentProvider.getDocument( editorInput );
            final int offset = region.getOffset();
            final IRegion wordRegion = JavaWordFinder.findWord( document, offset );

            if( isRegionValid( document, wordRegion ) )
            {
                IJavaElement[] elements = new IJavaElement[0];
                final long modStamp = documentProvider.getModificationStamp( editorInput );

                if( input.equals( this.lastInput ) && modStamp == this.lastModStamp &&
                    wordRegion.equals( this.lastWordRegion ) )
                {
                    elements = this.lastElements;
                }
                else
                {
                    try
                    {
                        elements = ( (ICodeAssist) input ).codeSelect( wordRegion.getOffset(), wordRegion.getLength() );
                        elements = selectOpenableElements( elements );
                        this.lastInput = input;
                        this.lastModStamp = modStamp;
                        this.lastWordRegion = wordRegion;
                        this.lastElements = elements;
                    }
                    catch( JavaModelException e )
                    {
                    }
                }

                if( elements.length != 0 )
                {
                    final List<IHyperlink> links = new ArrayList<IHyperlink>( elements.length );

                    for( IJavaElement element : elements )
                    {
                        if( element instanceof IMethod )
                        {
                            addHyperlinks(
                                links, wordRegion, (SelectionDispatchAction) openAction, (IMethod) element,
                                elements.length > 1, (JavaEditor) textEditor );
                        }
                    }

                    if( links.size() != 0 )
                    {
                        if( canShowMultipleHyperlinks )
                        {
                            retval = links.toArray( new IHyperlink[0] );
                        }
                        else
                        {
                            retval = new IHyperlink[] { links.get( 0 ) };
                        }
                    }
                }
            }
        }

        return retval;
    }

    @Override
    public void dispose()
    {
        super.dispose();
        this.lastElements = null;
        this.lastInput = null;
        this.lastWordRegion = null;
    }

    private IType findImplType( IJavaElement parent, String fullyQualifiedName ) throws JavaModelException
    {
        IType retval = parent.getJavaProject().findType( fullyQualifiedName );

        if( retval == null )
        {
            final IJavaProject[] serviceProjects = ServiceUtil.getAllServiceProjects();

            for( final IJavaProject sp : serviceProjects )
            {
                try
                {
                    retval = sp.findType( fullyQualifiedName );
                }
                catch( Exception e )
                {
                }

                if( retval != null )
                {
                    break;
                }
            }
        }

        return retval;
    }

    private IMethod getServiceImplMethod( final IMethod method )
    {
        IMethod retval = null;

        try
        {
            final IJavaElement methodClass = method.getParent();
            final IType methodClassType = method.getDeclaringType();
            final String methodClassName = methodClass.getElementName();

            if( methodClassName.endsWith( "Util" ) && JdtFlags.isPublic( method ) && JdtFlags.isStatic( method ) )
            {
                final String packageName = methodClassType.getPackageFragment().getElementName();
                final String baseServiceName = methodClassName.substring( 0, methodClassName.length() - 4 );
                // as per liferay standard real implementation will be in impl package and Impl suffix
                // e.g. com.example.service.FooUtil.getBar() --> com.example.service.impl.FooImpl.getBar()
                final String fullyQualifiedName = packageName + ".impl." + baseServiceName + "Impl";
                final IType implType = findImplType( methodClass, fullyQualifiedName );

                if( implType != null )
                {
                    IMethod[] methods = implType.findMethods( method );

                    if( CoreUtil.isNullOrEmpty( methods ) )
                    {
                        final ITypeHierarchy hierarchy = implType.newSupertypeHierarchy( new NullProgressMonitor() );
                        IType currentType = implType;

                        while( retval == null && currentType != null )
                        {
                            methods = currentType.findMethods( method );// match name and arguments

                            if( ! CoreUtil.isNullOrEmpty( methods ) )
                            {
                                retval = methods[0];
                            }
                            else
                            {
                                currentType = hierarchy.getSuperclass( currentType );
                            }
                        }
                    }
                    else
                    {
                        retval = methods[0];
                    }
                }
            }
        }
        catch( Exception e )
        {
        }

        return retval;
    }

    private boolean isInheritDoc( IDocument document, IRegion wordRegion )
    {
        try
        {
            String word = document.get( wordRegion.getOffset(), wordRegion.getLength() );
            return "inheritDoc".equals( word );
        }
        catch( BadLocationException e )
        {
            return false;
        }
    }

    private boolean isRegionValid( IDocument document, IRegion wordRegion )
    {
        if( wordRegion != null && wordRegion.getLength() != 0 && ( !isInheritDoc( document, wordRegion ) ) )
        {
            return true;
        }

        return false;
    }

    private IJavaElement[] selectOpenableElements( IJavaElement[] elements )
    {
        final List<IJavaElement> result = new ArrayList<IJavaElement>( elements.length );

        for( int i = 0; i < elements.length; i++ )
        {
            final IJavaElement element = elements[i];
            switch( element.getElementType() )
            {
            case IJavaElement.PACKAGE_DECLARATION:
            case IJavaElement.PACKAGE_FRAGMENT:
            case IJavaElement.PACKAGE_FRAGMENT_ROOT:
            case IJavaElement.JAVA_PROJECT:
            case IJavaElement.JAVA_MODEL:
                break;
            default:
                result.add( element );
                break;
            }
        }

        return result.toArray( new IJavaElement[result.size()] );
    }

    private boolean shouldAddServiceHyperlink( final JavaEditor editor  )
    {
        return SelectionConverter.canOperateOn( editor );
    }

    private boolean shouldDetectHyperlinks(
        final ITextEditor textEditor, final ITypeRoot input, final IAction openAction, final IRegion region )
    {
        return region != null && textEditor instanceof JavaEditor && openAction instanceof SelectionDispatchAction &&
            input != null;
    }

}
