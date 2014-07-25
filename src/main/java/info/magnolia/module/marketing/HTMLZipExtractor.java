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
package info.magnolia.module.marketing;

import info.magnolia.cms.beans.config.MIMEMapping;
import info.magnolia.cms.core.Path;
import info.magnolia.cms.util.PathUtil;
import info.magnolia.context.Context;
import info.magnolia.i18nsystem.SimpleTranslator;
import info.magnolia.jcr.util.NodeTypes;
import info.magnolia.jcr.util.NodeUtil;
import info.magnolia.ui.form.field.upload.UploadReceiver;
import info.magnolia.ui.framework.command.ImportZipCommand;
import info.magnolia.ui.vaadin.integration.jcr.JcrNewNodeAdapter;
import info.magnolia.ui.vaadin.integration.jcr.JcrNodeAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.jcr.Binary;
import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.collections.EnumerationUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.vaadin.easyuploads.UploadField;

import com.vaadin.data.Property;

/**
 * Extracts the uploaded ZIP file and stores the content into resources/campaign
 * workspace.
 * 
 * @author schulteja
 *
 */
public class HTMLZipExtractor extends ImportZipCommand {

    private InputStream inputStream;

    protected Context context;

    public HTMLZipExtractor(SimpleTranslator translator) {
        super(translator);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean execute(Context context) throws Exception {
        this.context = context;
        File tmpFile = null;
        FileOutputStream tmpStream = null;
        try {
            tmpFile = File.createTempFile(ZIP_TMP_FILE_PREFIX, ZIP_TMP_FILE_SUFFIX);
            tmpStream = new FileOutputStream(tmpFile);
            IOUtils.copy(inputStream, tmpStream);
        } catch (IOException e) {
            log.error("Failed to dump zip file to temp file: ", e);
            throw e;
        } finally {
            IOUtils.closeQuietly(tmpStream);
            IOUtils.closeQuietly(inputStream);
        }

        if (isValid(tmpFile)) {
            ZipFile zip = new ZipFile(tmpFile, getEncoding());
            // We use the ant-1.6.5 zip package to workaround encoding issues of
            // the sun implementation
            // (http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4244499)
            // For some reason, entries are not in the opposite order as how
            // they appear in most tools - reversing here.
            // Note that java.util.zip does not show this behaviour, and
            // ant-1.7.1 seems to enumerate entries in alphabetical or random
            // order.
            // Another alternative might be http://truezip.dev.java.net
            final List<ZipArchiveEntry> zipEntries = EnumerationUtils.toList(zip.getEntries());
            Collections.sort(zipEntries, new Comparator() {
                @Override
                public int compare(Object first, Object second) {
                    Boolean isFirstDirectory = ((ZipArchiveEntry) first).isDirectory();
                    Boolean isSecondDirectory = ((ZipArchiveEntry) second).isDirectory();
                    return isFirstDirectory.compareTo(isSecondDirectory);
                }
            });

            Collections.reverse(zipEntries);
            final Iterator it = zipEntries.iterator();

            final Iterator test = zipEntries.iterator();
            while (test.hasNext()) {
                ZipArchiveEntry entry = (ZipArchiveEntry) test.next();
                System.err.println(entry.getName());
            }

            while (it.hasNext()) {
                ZipArchiveEntry entry = (ZipArchiveEntry) it.next();
                processEntry(zip, entry);
            }
            context.getJCRSession(getRepository()).save();
        }
        return false;
    }

    private void processEntry(ZipFile zip, ZipArchiveEntry entry) throws IOException, RepositoryException {
        if (entry.getName().startsWith("__MACOSX")) {
            return;
        } else if (entry.getName().endsWith(".DS_Store")) {
            return;
        }

        if (entry.isDirectory()) {
            ensureFolder(entry);
        } else {
            handleFileEntry(zip, entry);
        }
    }

    protected void handleFileEntry(ZipFile zip, ZipArchiveEntry entry) throws IOException, RepositoryException {
        String fileName = entry.getName();

        if (StringUtils.contains(fileName, "/")) {
            fileName = StringUtils.substringAfterLast(fileName, "/");
        }

        String extension = StringUtils.substringAfterLast(fileName, ".");
        InputStream stream = zip.getInputStream(entry);
        FileOutputStream os = null;
        try {
            UploadReceiver receiver = createReceiver();

            String folderPath = extractEntryPath(entry);
            // Ensure relative part
            if (folderPath.startsWith("/")) {
                folderPath = folderPath.substring(1);
            }
            Node folder = StringUtils.isBlank(folderPath) ? getJCRNode(context) : getJCRNode(context).getNode(folderPath);
            receiver.setFieldType(UploadField.FieldType.BYTE_ARRAY);
            receiver.receiveUpload(fileName, StringUtils.defaultIfEmpty(MIMEMapping.getMIMEType(extension), DEFAULT_MIME_TYPE));
            receiver.setValue(IOUtils.toByteArray(stream));

            // Resources workspace
            Session resourcesSession = context.getJCRSession("resources");
            Node resourceRootNode = resourcesSession.getRootNode();
            Node resourcesNode = null;

            if (folderPath.length() > 0 && resourceRootNode.hasNode(folderPath)) {
                resourcesNode = resourceRootNode.getNode(folderPath);
            } else {
                Node n = getJCRNode(context);
                resourcesNode = resourcesNode.getNode(n.getPath());
            }

            // Save into app workspace
            doHandleEntryFromReceiver(folder, receiver);
            if (!fileName.toLowerCase().endsWith(".html")) {
                // Save in resources
                doHandleEntryFromReceiver(resourcesNode, receiver);
            }

            resourcesSession.save();

        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        } finally {
            IOUtils.closeQuietly(stream);
            IOUtils.closeQuietly(os);
        }
    }

    @Override
    protected void doHandleEntryFromReceiver(Node parentFolder, UploadReceiver receiver) throws RepositoryException {
        JcrNodeAdapter folderNodeAdapter = new JcrNodeAdapter(parentFolder);

        String newAssetName = receiver.getFileName();

        JcrNewNodeAdapter assetItem = new JcrNewNodeAdapter(parentFolder, NodeTypes.Content.NAME, newAssetName);
        assetItem.setNodeName(newAssetName);

        folderNodeAdapter.addChild(assetItem);

        Node newAssetNode = assetItem.applyChanges();
        newAssetNode.setProperty("mgnl:template", "resources:binary");

        // Node putFile = JcrUtils.putFile(newAssetNode, "binary", "text/html",
        // receiver.getContentAsStream());
        // //putFile.setProperty("jcr:data", binary);
        // putFile.getSession().save();
        //

        Node nodeImage = newAssetNode.addNode("binary", "mgnl:resource");
        Binary binary = parentFolder.getSession().getValueFactory().createBinary(receiver.getContentAsStream());
        nodeImage.setProperty("jcr:data", binary);
        nodeImage.setProperty("jcr:mimeType", "text/html");
    }

    private String extractFileName(JcrNodeAdapter resourceNode) {
        String fileName = null;
        Property<?> fileNameProperty = resourceNode.getItemProperty(NodeTypes.Resource.NAME);
        if (fileNameProperty != null) {
            fileName = PathUtil.stripExtension((String) fileNameProperty.getValue());
        }
        return fileName;
    }

    /**
     * Create a new Node Unique NodeName.
     */
    private String generateUniqueNodeNameForAsset(final Item item, String newNodeName) throws RepositoryException {
        return Path.getUniqueLabel(item.getSession(), item.getParent().getPath(), Path.getValidatedLabel(newNodeName));
    }

    private void ensureFolder(ZipArchiveEntry entry) throws RepositoryException {
        String path = extractEntryPath(entry);
        Node jcrNode = getJCRNode(context);
        NodeUtil.createPath(jcrNode, path, NodeTypes.Folder.NAME, true);
        // Copy in the resources folder
        Session resourcesSession = context.getJCRSession("resources");
        Node rootNode = resourcesSession.getRootNode();

        NodeUtil.createPath(rootNode, path, NodeTypes.Folder.NAME, true);
        resourcesSession.save();

    }

    private String extractEntryPath(ZipArchiveEntry entry) {
        String entryName = entry.getName();
        String path = (StringUtils.contains(entryName, "/")) ? StringUtils.substringBeforeLast(entryName, "/") : "/";

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // make proper name, the path was already created
        path = StringUtils.replace(path, "/", BACKSLASH_DUMMY);
        path = Path.getValidatedLabel(path);
        path = StringUtils.replace(path, BACKSLASH_DUMMY, "/");
        return path;
    }

    private boolean isValid(File tmpFile) {
        return tmpFile != null;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

}
