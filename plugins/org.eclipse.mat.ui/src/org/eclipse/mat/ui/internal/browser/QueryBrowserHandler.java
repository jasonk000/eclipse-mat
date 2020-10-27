/*******************************************************************************
 * Copyright (c) 2008, 2020 SAP AG and IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    Andrew Johnson (IBM Corporation) - allow parameters to initialize with a particular command
 *******************************************************************************/
package org.eclipse.mat.ui.internal.browser;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.mat.ui.editor.MultiPaneEditor;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public class QueryBrowserHandler extends AbstractHandler
{

    public QueryBrowserHandler()
    {}

    public Object execute(ExecutionEvent executionEvent)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;

        IEditorPart activeEditor = page.getActiveEditor();
        if (!(activeEditor instanceof MultiPaneEditor))
            return null;

        QueryBrowserPopup qbp = new QueryBrowserPopup((MultiPaneEditor) activeEditor);
        String commandName = executionEvent.getParameter("org.eclipse.mat.ui.query.browser.QueryBrowser.commandName"); //$NON-NLS-1$
        if (commandName != null)
        {
            Control c = qbp.getFocusControl();
            if (c instanceof Text)
            {
                Text t = (Text)c;
                t.setText(commandName);
            }
        }
        qbp.open();

        return null;
    }
}
