/**
 * This file Copyright (c) 2008-2014 Magnolia International
 * Ltd.  (http://www.magnolia-cms.com). All rights reserved.
 *
 *
 * This file is dual-licensed under both the Magnolia
 * Network Agreement and the GNU General Public License.
 * You may elect to use one or the other of these licenses.
 *
 * This file is distributed in the hope that it will be
 * useful, but AS-IS and WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE, TITLE, or NONINFRINGEMENT.
 * Redistribution, except as permitted by whichever of the GPL
 * or MNA you select, is prohibited.
 *
 * 1. For the GPL license (GPL), you can redistribute and/or
 * modify this file under the terms of the GNU General
 * Public License, Version 3, as published by the Free Software
 * Foundation.  You should have received a copy of the GNU
 * General Public License, Version 3 along with this program;
 * if not, write to the Free Software Foundation, Inc., 51
 * Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * 2. For the Magnolia Network Agreement (MNA), this file
 * and the accompanying materials are made available under the
 * terms of the MNA which accompanies this distribution, and
 * is available at http://www.magnolia-cms.com/mna.html
 *
 * Any modifications to this file must keep this entire header
 * intact.
 *
 */
package info.magnolia.module.marketing.action;

import info.magnolia.cms.core.Path;
import info.magnolia.context.MgnlContext;
import info.magnolia.jcr.util.NodeTypes;
import info.magnolia.jcr.util.NodeUtil;
import info.magnolia.ui.admincentral.dialog.action.SaveDialogAction;
import info.magnolia.ui.api.action.AbstractAction;
import info.magnolia.ui.api.action.ActionExecutionException;
import info.magnolia.ui.form.EditorCallback;
import info.magnolia.ui.form.EditorValidator;
import info.magnolia.ui.vaadin.integration.jcr.JcrNodeAdapter;
import info.magnolia.ui.vaadin.integration.jcr.ModelConstants;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.data.Item;

/**
 * Creates the pages according to the imported HTML files.
 * 
 * @author schulteja
 * @param <T>
 */
public class CreatePagesAction<T extends CreatePagesActionDefinition> extends AbstractAction<T> {

    private static final Logger log = LoggerFactory.getLogger(SaveDialogAction.class);

    protected final Item item;

    protected final EditorValidator validator;
    protected final EditorCallback callback;

    public CreatePagesAction(T definition, Item item, EditorValidator validator, EditorCallback callback) {
        super(definition);
        this.item = item;
        this.validator = validator;
        this.callback = callback;
    }

    @Override
    public void execute() throws ActionExecutionException {
        // First Validate
        validator.showValidation(true);
        if (validator.isValid()) {
            final JcrNodeAdapter itemChanged = (JcrNodeAdapter) item;
            try {
                final Node node = itemChanged.applyChanges();
                setNodeName(node, itemChanged);
                node.getSession().save();

                Property targetPageProperty = node.getProperty("targetPage");
                Node pageRootNode = NodeUtil.getNodeByIdentifier("website", targetPageProperty.getValue().getString());
                System.out.println(pageRootNode.getPath());
                // get starting point from html prototype
                Node parent = node.getParent();
                NodeIterator nodes = parent.getNodes();
                // Create the root node for the import
                createIndexNode(pageRootNode, parent);
                while (nodes.hasNext()) {
                    Node resourcesNode = (Node) nodes.next();
                    // Create pages for HTML
                    if (resourcesNode.getName().endsWith("html")) {
                        createPage(pageRootNode, resourcesNode);
                    }
                }

            } catch (final RepositoryException e) {
                throw new ActionExecutionException(e);
            } catch (Exception e) {
                e.printStackTrace();
            }
            callback.onSuccess(getDefinition().getName());
        } else {
            log.info("Validation error(s) occurred. No save performed.");
        }
    }

    /**
     * Node for index.html.
     * 
     * @param pagesStartNode
     * @param resourcesHTMLNode
     * @throws Exception
     */
    protected void createIndexNode(Node pagesStartNode, Node resourcesHTMLNode) throws Exception {
        String campaignName = getCampaignName(resourcesHTMLNode);
        Node indexNode = pagesStartNode.addNode(campaignName, NodeTypes.Page.NAME);
        indexNode.setProperty("mgnl:template", "magnolia-campaign-import:pages/htmlBridge");
        // Get resource Node identifier
        String query = "select * from [mgnl:content] as t where ISDESCENDANTNODE([/" + campaignName + "]) and name(t) = 'index.html'";
        QueryManager queryManager = MgnlContext.getJCRSession("campaigns").getWorkspace().getQueryManager();
        Query indexQuery = queryManager.createQuery(query, Query.JCR_SQL2);
        Node resourceIndexNode = (Node) indexQuery.execute().getNodes().next();
        indexNode.setProperty("htmlTemplate", resourceIndexNode.getIdentifier());
        indexNode.setProperty("originalHtmlName", resourceIndexNode.getName());
        indexNode.getSession().save();

    }

    protected String getCampaignName(Node resourcesHTMLNode) throws RepositoryException {
        if (resourcesHTMLNode.getDepth() <= 1) {
            return resourcesHTMLNode.getName();
        }

        while (resourcesHTMLNode.getDepth() > 1) {
            resourcesHTMLNode = resourcesHTMLNode.getParent();
        }
        return resourcesHTMLNode.getName();
    }

    protected void createPage(Node pagesStartNode, Node resourcesHTMLNode) throws Exception {

        // Root node here?
        String resourcesPath = resourcesHTMLNode.getPath().replaceFirst("/", "");
        String campaignName = getCampaignName(resourcesHTMLNode);
        resourcesPath = resourcesPath.replace(campaignName.replace(".html", ""), "").replaceFirst("/", "");
        resourcesPath = resourcesPath.replace(".html", "");
        if (resourcesHTMLNode.getName().contains("index.html")) {
            return;
        }

        Node newPageNode = pagesStartNode.addNode(resourcesPath, NodeTypes.Page.NAME);

        newPageNode.setProperty("htmlTemplate", resourcesHTMLNode.getIdentifier());
        newPageNode.setProperty("mgnl:template", "campaign-import:pages/htmlBridge");
        newPageNode.setProperty("originalHtmlName", resourcesHTMLNode.getName());
        newPageNode.getSession().save();
    }

    /**
     * Set the node Name. Node name is set to: <br>
     * the value of the property 'name' if it is present.
     */
    protected void setNodeName(Node node, JcrNodeAdapter item) throws RepositoryException {
        String propertyName = "name";
        if (node.hasProperty(propertyName) && !node.hasProperty(ModelConstants.JCR_NAME)) {
            Property property = node.getProperty(propertyName);
            String newNodeName = property.getString();
            if (!node.getName().equals(Path.getValidatedLabel(newNodeName))) {
                newNodeName = Path.getUniqueLabel(node.getSession(), node.getParent().getPath(), Path.getValidatedLabel(newNodeName));
                item.setNodeName(newNodeName);
                NodeUtil.renameNode(node, newNodeName);
            }
        }
    }
}
