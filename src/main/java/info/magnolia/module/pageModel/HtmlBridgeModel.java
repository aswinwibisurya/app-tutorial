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
package info.magnolia.module.pageModel;

import info.magnolia.context.MgnlContext;
import info.magnolia.jcr.util.NodeUtil;
import info.magnolia.rendering.model.RenderingModel;
import info.magnolia.rendering.model.RenderingModelImpl;
import info.magnolia.rendering.template.RenderableDefinition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Acquire source HTML adopt paths.
 * 
 * @author schulteja
 *
 * @param <RD>
 */
public class HtmlBridgeModel<RD extends RenderableDefinition> extends RenderingModelImpl<RD> {

    Node content = null;

    public HtmlBridgeModel(Node content, RD definition, RenderingModel<?> parent) {
        super(content, definition, parent);
        this.content = content;
    }

    /**
     * Acquire HTML from workspace, adopt paths and send to renderer.
     * 
     * @return
     * @throws LoginException
     * @throws NoSuchWorkspaceException
     * @throws RepositoryException
     */
    public String getHTMLContent() throws LoginException, NoSuchWorkspaceException, RepositoryException {
        Property htmlTemplate = content.getProperty("htmlTemplate");
        String templateUUID = htmlTemplate.getValue().getString();
        Node nodeByIdentifier = NodeUtil.getNodeByIdentifier("campaigns", templateUUID);
        Node binaryNode = nodeByIdentifier.getNode("binary");
        InputStream fileStream = binaryNode.getProperty("jcr:data").getBinary().getStream();

        String html = getStringFromInputStream(fileStream);
        Document doc = Jsoup.parse(html);
        doc.outputSettings(new Document.OutputSettings().prettyPrint(true));// makes
        // html()
        // preserve
        // linebreaks
        // and
        // spacing
        String sitePath = MgnlContext.getWebContext().getContextPath() + "/resources" + getLevel1Parent(nodeByIdentifier).getPath() + "/";

        // CSS
        Elements cssLinks = doc.select("link");
        for (Element link : cssLinks) {
            String href = link.attr("href").toString();
            if (href.startsWith("#") || href.startsWith("http") || href.length() == 0) {
                continue;
            }
            String hrefValue = sitePath + link.attr("href");
            link.attr("href", hrefValue);
        }

        // a
        Elements links = doc.select("a");
        for (Element link : links) {
            String href = link.attr("href").toString();
            if (href.startsWith("#") || href.startsWith("http")) {
                continue;
            }

            String contentPath = content.getPath();
            contentPath = contentPath.substring(0, contentPath.lastIndexOf("/"));

            // Get resource Node identifier
            String query = "select * from [mgnl:page] as t where ISDESCENDANTNODE([" + content.getParent().getPath() + "]) and originalHtmlName like '" + href + "'";
            QueryManager queryManager = MgnlContext.getJCRSession("website").getWorkspace().getQueryManager();
            Query indexQuery = queryManager.createQuery(query, Query.JCR_SQL2);
            NodeIterator nodes = indexQuery.execute().getNodes();

            if (!nodes.hasNext()) {
                continue;
            }
            Node resourceIndexNode = (Node) nodes.next();

            String hrefValue = MgnlContext.getContextPath() + contentPath + "/" + resourceIndexNode.getName() + ".html";
            link.attr("href", hrefValue);
        }

        // JS
        Elements scripts = doc.select("script");
        for (Element script : scripts) {
            String src = script.attr("src").toString();
            if (src.startsWith("http") || src.length() == 0) {

                continue;
            }
            System.out.println(src);
            src = sitePath + src;
            src = src.replace("//", "/");

            script.attr("src", src);
        }

        // img
        Elements imgs = doc.select("img");
        for (Element img : imgs) {
            String src = img.attr("src").toString();
            if (src.startsWith("http")) {
                continue;
            }
            src = sitePath + src;
            src = src.replace("//", "/");
            img.attr("src", src);
        }

        String result = doc.html();
        return result;
    }

    protected Node getLevel1Parent(Node node) throws RepositoryException {
        if (node.getDepth() <= 1) {
            return node;
        }
        Node n = node;
        while (n.getDepth() > 1) {
            n = n.getParent();
        }
        return n;
    }

    /**
     * Convert InputStream to String.
     * 
     * @param is
     * @return
     */
    private static String getStringFromInputStream(InputStream is) {

        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();

        String line;
        try {
            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return sb.toString();
    }

}
