package org.gatein.wcm.impl.jcr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import javax.jcr.AccessDeniedException;
import javax.jcr.Binary;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.gatein.wcm.api.model.publishing.PublishStatus;
import org.gatein.wcm.api.model.security.ACE;
import org.gatein.wcm.api.model.security.ACE.PermissionType;
import org.gatein.wcm.api.model.security.ACL;
import org.gatein.wcm.api.model.security.Principal;
import org.gatein.wcm.api.model.security.Principal.PrincipalType;
import org.gatein.wcm.api.model.security.User;
import org.gatein.wcm.api.services.exceptions.ContentException;
import org.gatein.wcm.api.services.exceptions.ContentIOException;
import org.gatein.wcm.api.services.exceptions.ContentSecurityException;
import org.gatein.wcm.impl.model.WcmConstants;
import org.gatein.wcm.impl.model.WcmContentFactory;
import org.jboss.logging.Logger;

/**
 *
 * All JCR low level operations should be placed here.
 *
 * @author lucas
 *
 */
public class JcrMappings {

    private static final Logger log = Logger.getLogger(JcrMappings.class);

    private final String MARK = "__";
    private final int TEXT_SUMMARY = 1000;

    WcmContentFactory factory = null;

    Session jcrSession = null;
    User logged = null;
    VersionManager vm = null;

    public JcrMappings(Session session, User user) throws ContentIOException {
        try {
            jcrSession = session;
            logged = user;
            vm = jcrSession.getWorkspace().getVersionManager();
        } catch (RepositoryException e) {
            throw new ContentIOException("Unexpected error initializating session JCR objects. Msg: " + e.getMessage());
        }
    }

    public WcmContentFactory getFactory() {
        return factory;
    }

    public void setFactory(WcmContentFactory factory) {
        this.factory = factory;
    }

    public boolean checkSession() {
        if (this.jcrSession == null || this.logged == null)
            return false;
        return true;
    }

    public boolean checkLocation(String location) {

        if (location == null)
            return false;
        if (location.equals("/"))
            return true;

        try {
            return jcrSession.nodeExists(location);
        } catch (RepositoryException e) {
            log.error("Location " + location + " bad specified. Message: " + e.getMessage());
        }
        return false;
    }

    public boolean checkLocation(String location, String locale) {

        if (location == null)
            return false;
        if (location.equals("/"))
            return true;

        try {
            return jcrSession.nodeExists(location + "/" + MARK + locale);
        } catch (RepositoryException e) {
            log.error("Location " + location + " bad specified. Message: " + e.getMessage());
        }
        return false;
    }

    public boolean checkIdExists(String location, String id, String locale) {
        try {
            String tmpLocation = location;
            if ("/".equals(location))
                tmpLocation = "";
            Node root = jcrSession.getNode(tmpLocation + "/" + id + "/" + MARK + locale);
            if (root.getPrimaryNodeType().getName().equals("nt:folder"))
                return true;
        } catch (PathNotFoundException e) {
            return false;
        } catch (RepositoryException e) {
            log.error("Unexpected error in location " + location + "/" + id + "/" + MARK + locale + ". Message: "
                    + e.getMessage());
        }
        return false;
    }

    public boolean checkIdExists(String location, String id) {
        try {
            String tmpLocation = ("/".equals(location)?"":location);
            Node root = jcrSession.getNode(tmpLocation + "/" + id);
            if (root.getPrimaryNodeType().getName().equals("nt:folder"))
                return true;
        } catch (PathNotFoundException e) {
            return false;
        } catch (RepositoryException e) {
            log.error("Unexpected error in location " + location + "/" + id + ". Message: " + e.getMessage());
        }
        return false;
    }

    public boolean checkUserWriteACL(String location) {

        // Create ACL from location
        ACL acl = null;
        try {
            acl = jcrACL(location);
            // If there are not __acl folder in the location, we will check to the parent node
            if (acl == null && !"/".equals(location))
                return checkUserWriteACL(parent(location));
            if (acl == null && "/".equals("/"))
                return true;
        } catch (RepositoryException e) {
            log.error("Unexpected error looking for acl in location " + location + ". Msg: " + e.getMessage());
            return false;
        }

        // Validate ACL with logged user
        for (ACE ace : acl.getAces()) {
            // Check if we have a GROUP ACE
            if (ace.getPrincipal().getType() == PrincipalType.GROUP
                    && Arrays.asList(PermissionType.WRITE, PermissionType.ALL).contains(ace.getPermission())) {
                for (String group : logged.getGroups())
                    if (group.equals(ace.getPrincipal().getId()))
                        return true;
            }
            // Check if we have a USER ACE
            if (ace.getPrincipal().getType() == PrincipalType.USER && ace.getPrincipal().getId().equals(logged.getUserName())
                    && Arrays.asList(PermissionType.WRITE, PermissionType.ALL).contains(ace.getPermission()))
                return true;
        }

        return false;
    }

    public boolean checkUserReadACL(String location) {

        // Create ACL from location
        ACL acl = null;
        try {
            acl = jcrACL(location);
            // If there are not __acl folder in the location, we will check to the parent node
            if (acl == null && !"/".equals(location))
                return checkUserReadACL(parent(location));
            if (acl == null && "/".equals("/"))
                return true;
        } catch (RepositoryException e) {
            log.error("Unexpected error looking for acl in location " + location + ". Msg: " + e.getMessage());
            return false;
        }

        // Validate ACL with logged user
        for (ACE ace : acl.getAces()) {
            // Check if we have a GROUP ACE
            if (ace.getPrincipal().getType() == PrincipalType.GROUP
                    && Arrays.asList(PermissionType.READ, PermissionType.COMMENTS, PermissionType.WRITE, PermissionType.ALL)
                            .contains(ace.getPermission())) {
                for (String group : logged.getGroups())
                    if (group.equals(ace.getPrincipal().getId()))
                        return true;
            }
            // Check if we have a USER ACE
            if (ace.getPrincipal().getType() == PrincipalType.USER
                    && ace.getPrincipal().getId().equals(logged.getUserName())
                    && Arrays.asList(PermissionType.READ, PermissionType.COMMENTS, PermissionType.WRITE, PermissionType.ALL)
                            .contains(ace.getPermission()))
                return true;
        }

        return false;
    }

    public boolean checkUserAdminACL(String location) {

        // Create ACL from location
        ACL acl = null;
        try {
            acl = jcrACL(location);
            // If there are not __acl folder in the location, we will check to the parent node
            if (acl == null && !"/".equals(location))
                return checkUserAdminACL(parent(location));
            if (acl == null && "/".equals("/"))
                return true;
        } catch (RepositoryException e) {
            log.error("Unexpected error looking for acl in location " + location + ". Msg: " + e.getMessage());
            return false;
        }

        // Validate ACL with logged user
        for (ACE ace : acl.getAces()) {
            // Check if we have a GROUP ACE
            if (ace.getPrincipal().getType() == PrincipalType.GROUP
                    && Arrays.asList(PermissionType.ALL).contains(ace.getPermission())) {
                for (String group : logged.getGroups())
                    if (group.equals(ace.getPrincipal().getId()))
                        return true;
            }
            // Check if we have a USER ACE
            if (ace.getPrincipal().getType() == PrincipalType.USER && ace.getPrincipal().getId().equals(logged.getUserName())
                    && Arrays.asList(PermissionType.ALL).contains(ace.getPermission()))
                return true;
        }

        return false;
    }

    public boolean checkUserCommentsACL(String location) {

        // Create ACL from location
        ACL acl = null;
        try {
            acl = jcrACL(location);
            // If there are not __acl folder in the location, we will check to the parent node
            if (acl == null && !"/".equals(location))
                return checkUserCommentsACL(parent(location));
            if (acl == null && "/".equals("/"))
                return true;
        } catch (RepositoryException e) {
            log.error("Unexpected error looking for acl in location " + location + ". Msg: " + e.getMessage());
            return false;
        }

        // Validate ACL with logged user
        for (ACE ace : acl.getAces()) {
            // Check if we have a GROUP ACE
            if (ace.getPrincipal().getType() == PrincipalType.GROUP
                    && Arrays.asList(PermissionType.COMMENTS, PermissionType.WRITE, PermissionType.ALL).contains(
                            ace.getPermission())) {
                for (String group : logged.getGroups())
                    if (group.equals(ace.getPrincipal().getId()))
                        return true;
            }
            // Check if we have a USER ACE
            if (ace.getPrincipal().getType() == PrincipalType.USER
                    && ace.getPrincipal().getId().equals(logged.getUserName())
                    && Arrays.asList(PermissionType.COMMENTS, PermissionType.WRITE, PermissionType.ALL).contains(
                            ace.getPermission()))
                return true;
        }

        return false;
    }

    public void checkJCRException(RepositoryException e) throws ContentException, ContentIOException, ContentSecurityException {
        if (e instanceof PathNotFoundException) {
            throw new ContentException("Location doesn't found. Msg: " + e.getMessage());
        }
        if (e instanceof ItemExistsException) {
            throw new ContentException("Item exists. Msg: " + e.getMessage());
        }
        if (e instanceof NoSuchNodeTypeException) {
            throw new ContentException("Trying to write in a different node type. Msg: " + e.getMessage());
        }
        if (e instanceof LockException) {
            throw new ContentSecurityException("Trying to write in a lock node. Msg: " + e.getMessage());
        }
        if (e instanceof VersionException) {
            throw new ContentSecurityException("Error in versioning. Msg: " + e.getMessage());
        }
        if (e instanceof ConstraintViolationException) {
            throw new ContentSecurityException("Unexpected constraint violation. Msg: " + e.getMessage());
        }
        if (e instanceof ValueFormatException) {
            throw new ContentException("Wrong value format. Msg: " + e.getMessage());
        }
        if (e instanceof AccessDeniedException) {
            throw new ContentSecurityException("Access denied. Msg: " + e.getMessage());
        }
        if (e instanceof ReferentialIntegrityException) {
            throw new ContentException("Unexpected referencial integrity. Msg: " + e.getMessage());
        }
        throw new ContentIOException("Unexpected repository error. Msg: " + e.getMessage());
    }

    public boolean checkLocaleContent(String location) {
        try {
            Node n = jcrSession.getNode(location);

            String description = null;
            try {
                if (n.getProperty("jcr:description") != null)
                    description = n.getProperty("jcr:description").getString();
                // Description property has a <type>:<path>
                description = description.split(":")[0];
            } catch (PathNotFoundException e) {
                // This node has not mix:title, so exception ignored
            }

            // Only TextContent or BinaryContent has locales
            if (description != null && ("textcontent".contains(description) || "binarycontent".contains(description))) {
                return true;
            }
        } catch (Exception e) {
            log.error("Unexpected error looking for locales. Msg: " + e.getMessage());
        }
        return false;
    }

    public boolean checkCategoryReferences(String categoryLocation) {
        try {
            Node n = jcrSession.getNode("/__categories" + categoryLocation + "/__references");
            // boolean hasChilds = n.getNodes().hasNext();
            // Debug code
            NodeIterator ni = n.getNodes();
            if (ni.hasNext()) {
                ni.nextNode();
                return true;
            } else
                return false;
        } catch (NoSuchElementException e) {
            return false;
        } catch (Exception e) {
            log.error("Unexpected error loofing for references in Category: " + categoryLocation + ". Msg: " + e.getMessage());
        }
        return false;
    }

    public void createTextNode(String id, String locale, String location, Value content) throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);
        String contentId = tmpLocation + "/" + id;
        String contentLocaleId = tmpLocation + "/" + id + "/" + MARK + locale + "/" + MARK + id;

        Node n;

        if (!checkIdExists(location, id)) {
            jcrSession.getNode(location).addNode(id, "nt:folder");

            n = jcrSession.getNode(tmpLocation + "/" + id);
            n.addMixin("mix:title");
            n.addMixin("mix:lastModified");
            n.addMixin("mix:shareable");
            n.addMixin("mix:versionable");
        } else n = jcrSession.getNode(tmpLocation + "/" + id);

        // Checking out for add new version of content
        vm.checkout(contentId);

        n.setProperty("jcr:description", "textcontent:" + n.getPath());

        jcrSession.getNode(contentId).addNode(MARK + locale, "nt:folder").addNode(MARK + id, "nt:file")
        .addNode("jcr:content", "nt:resource").setProperty("jcr:data", content);

        n = jcrSession.getNode(contentLocaleId);
        n.addMixin("mix:title");
        n.addMixin("mix:lastModified");
        n.addMixin("mix:mimeType");

        // Adding properties
        n.getNode("jcr:content").setProperty("jcr:data", content);

        // Description is a short summary of the real content
        if (content.getString().length() > TEXT_SUMMARY)
            n.setProperty("jcr:description", content.getString().substring(0, TEXT_SUMMARY));
        else
            n.setProperty("jcr:description", content.getString());

        // Saving changes into JCR
        jcrSession.save();

        // Checkin version
        vm.checkin(contentId);
    }

    public String deleteNode(String location) throws RepositoryException {

        // Check wcm node type
        boolean versionable = false;
        String description = jcrSession.getNode(location).getProperty("jcr:description").getString();
        String type = description.split(":")[0];
        if ("textcontent".equals(type) || "binarycontent".equals(type)) versionable = true;

        if (versionable) vm.checkout(location);

        // Metadata
        try {
            // This is a workaround, this method doesn't delete previous versions of the content
            jcrSession.removeItem(location);
            jcrSession.removeItem("/__comments" + location);
            jcrSession.removeItem("/__properties" + location);
            jcrSession.removeItem("/__acl" + location);
        } catch (PathNotFoundException expected) {
            // No comments or properties found - skip
        } finally {
            // Saving changes into JCR
            jcrSession.save();
            try {
                if (versionable) vm.checkin(location);
            } catch (Exception expected) {
                //  This is a bug in MODESHAPE when only I have a root version
            }
        }

        // Main content and versions
        // TODO We need to remove all versions in this method, right now this method doesn't work in current modeshape version
        // jcrDeleteVersions(location);

        return parent(location);
    }

    public String deleteNode(String location, String locale) throws RepositoryException {

        // Check wcm node type
        boolean versionable = false;
        String description = jcrSession.getNode(location).getProperty("jcr:description").getString();
        String type = description.split(":")[0];

        if ("textcontent".equals(type) || "binarycontent".equals(type)) versionable = true;
        if (versionable) vm.checkout(location);

        jcrSession.removeItem(location + "/" + MARK + locale);

        // Check if we have a textcontent or binarycontent orphan of locales, then we delete

        boolean orphan = true;
        Node n = jcrSession.getNode(location);
        NodeIterator ni = n.getNodes();
        while (ni.hasNext()) {
            Node child = ni.nextNode();
            String name = child.getName();
            if (!WcmConstants.RESERVED_ENTRIES.contains(name)) {
                if (name.startsWith("__"))
                    orphan = false;
            }
        }

        // Saving changes into JCR
        jcrSession.save();
        if (versionable) vm.checkin(location);

        // If I don't have locales I will delete whole location
        if (orphan) {
            return deleteNode(location);
        } else {
            // We still have locales under same location, so we return same node instead parent
            return location;
        }
    }

    public void createFolder(String id, String location) throws RepositoryException {
        Node n = jcrSession.getNode(location).addNode(id, "nt:folder");
        n.addMixin("mix:title");
        n.addMixin("mix:lastModified");
        n.addMixin("mix:shareable");

        n.setProperty("jcr:description", "folder:" + n.getPath());

        // Saving changes into JCR
        jcrSession.save();
    }

    public void createBinaryNode(String id, String locale, String location, String contentType, Long size, String fileName,
            InputStream content) throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);
        String contentId = tmpLocation + "/" + id;
        String contentLocaleId = tmpLocation + "/" + id + "/" + MARK + locale + "/" + MARK + id;

        Node n;

        if (!checkIdExists(location, id)) {
            jcrSession.getNode(location).addNode(id, "nt:folder");

            n = jcrSession.getNode(tmpLocation + "/" + id);
            n.addMixin("mix:title");
            n.addMixin("mix:lastModified");
            n.addMixin("mix:shareable");
            n.addMixin("mix:versionable");
        } else n = jcrSession.getNode(tmpLocation + "/" + id);

        // Checking out for add new version of content
        vm.checkout(contentId);

        // Adding type of node and abs path in description
        n.setProperty("jcr:description", "binarycontent:" + n.getPath());

        jcrSession.getNode(contentId).addNode(MARK + locale, "nt:folder").addNode(MARK + id, "nt:file")
                .addNode("jcr:content", "nt:resource");

        n = jcrSession.getNode(contentLocaleId);
        n.addMixin("mix:title");
        n.addMixin("mix:lastModified");
        n.addMixin("mix:mimeType");

        // Adding properties
        Binary _content = jcrSession.getValueFactory().createBinary(content);

        n.getNode("jcr:content").setProperty("jcr:data", _content);
        n.setProperty("jcr:title", fileName);
        n.setProperty("jcr:mimeType", contentType);
        n.setProperty("jcr:description", size);

        // Saving changes into JCR
        jcrSession.save();

        // Checkin version
        vm.checkin(contentId);
    }

    // Read methods

    public Session getSession() {
        return this.jcrSession;
    }

    public List<String> getLocales(String location) throws RepositoryException {
        Node n = jcrSession.getNode(location);

        ArrayList<String> locales = new ArrayList<String>();

        NodeIterator ni = n.getNodes();
        while (ni.hasNext()) {
            Node child = ni.nextNode();
            String name = child.getName();
            if (!WcmConstants.RESERVED_ENTRIES.contains(name)) {
                if (name.startsWith("__"))
                    locales.add(name.substring(2));
            }
        }

        if (locales.isEmpty())
            return null;

        return locales;
    }

    public void updateTextNode(String location, String locale, Value content) throws RepositoryException {
        if ("/".equals(location))
            return;

        vm.checkout(location);

        String id = location.substring(location.lastIndexOf("/") + 1);
        Node n = jcrSession.getNode(location + "/" + MARK + locale + "/" + MARK + id);

        // In TextNodes we store the html also in jcr:description for future search funtionality
        n.setProperty("jcr:description", content);
        n.getNode("jcr:content").setProperty("jcr:data", content);

        jcrSession.save();
        vm.checkin(location);
    }

    public void updateFolderLocation(String location, String newLocation) throws RepositoryException {
        // Root node is not affected
        if ("/".equals(location))
            return;

        jcrSession.move(location, newLocation + "/");

        // Update abs path in the jcr:description
        Node n = jcrSession.getNode(newLocation);

        jcrUpdateDescriptionPath(n);

        jcrSession.save();
    }

    public void updateCategoryLocation(String location, String newLocation) throws RepositoryException {
        // Root node is not affected
        if ("/".equals(location))
            return;

        jcrSession.move(location, newLocation + "/");

        jcrSession.save();
    }

    public void updateFolderName(String location, String newName) throws RepositoryException {
        // Root node is not affected
        if ("/".equals(location))
            return;

        Node n = jcrSession.getNode(location);

        jcrSession.move(location, n.getParent().getPath() + "/" + newName);

        jcrUpdateDescriptionPath(n);

        jcrSession.save();
    }

    public void updateBinaryNode(String location, String locale, String contentType, Long size, String fileName,
            InputStream content) throws RepositoryException {
        // Root node is not affected
        if ("/".equals(location))
            return;

        vm.checkout(location);

        String id = location.substring(location.lastIndexOf("/") + 1);

        Node n = jcrSession.getNode(location + "/" + MARK + locale + "/" + MARK + id);

        Binary _content = jcrSession.getValueFactory().createBinary(content);

        n.getNode("jcr:content").setProperty("jcr:data", _content);
        n.setProperty("jcr:title", fileName);
        n.setProperty("jcr:mimeType", contentType);
        n.setProperty("jcr:description", size);

        jcrSession.save();
        vm.checkin(location);
    }

    public void createCategory(String id, String locale, String fullLocation, String description) throws RepositoryException {
        if (!checkIdExists(fullLocation, id)) {
            jcrSession.getNode(fullLocation).addNode(id, "nt:folder");
            jcrSession.getNode(fullLocation + "/" + id).addNode("__references", "nt:folder").addMixin("mix:shareable");
        }

        jcrSession.getNode(fullLocation + "/" + id).addNode(MARK + locale, "nt:folder");

        Node n = jcrSession.getNode(fullLocation + "/" + id + "/" + MARK + locale);
        n.addMixin("mix:title");
        n.addMixin("mix:lastModified");
        n.setProperty("jcr:description", description);

        jcrSession.save();
    }

    public void updateCategory(String fullLocation, String locale, String description) throws RepositoryException {
        Node n = jcrSession.getNode(fullLocation + "/" + MARK + locale);
        n.setProperty("jcr:description", description);

        jcrSession.save();
    }

    public void deleteCategory(String fullLocation) throws RepositoryException {
        jcrSession.removeItem(fullLocation);

        jcrSession.save();
    }

    public void deleteCategory(String fullLocation, String locale) throws RepositoryException {
        jcrSession.removeItem(fullLocation + "/" + MARK + locale);

        jcrSession.save();
    }

    public void createContentComment(String location, String comment) throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);
        Node n;
        try {
            n = jcrSession.getNode("/__comments" + tmpLocation);
        } catch (PathNotFoundException e) {
            n = jcrCreatePath("/__comments" + tmpLocation);
        }

        String idComment = "" + new Date().getTime() + "_" + UUID.randomUUID().toString();
        n.addNode(idComment, "nt:folder").addMixin("mix:title");
        n.getNode(idComment).setProperty("jcr:description", comment);
        jcrSession.save();
    }

    public void createContentProperty(String location, String locale, String name, String value) throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);
        Node n;
        try {
            n = jcrSession.getNode("/__properties" + tmpLocation + "/" + MARK + locale);
        } catch (PathNotFoundException e) {
            n = jcrCreatePath("/__properties" + tmpLocation + "/" + MARK + locale);
        }

        if (!n.hasNode(name)) {
            n = n.addNode(name, "nt:folder");
            n.addMixin("mix:title");

        }
        n.setProperty("jcr:description", value);

        jcrSession.save();
    }

    public void deleteContentComment(String location, String idComment) throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);

        if (jcrSession.itemExists("/__comments" + tmpLocation + "/" + idComment)) {
            jcrSession.removeItem("/__comments" + tmpLocation + "/" + idComment);
        }

        jcrSession.save();
    }

    public void deleteContentProperty(String location, String locale, String name) throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);

        if (jcrSession.itemExists("/__properties" + tmpLocation + "/" + MARK + locale + "/" + name)) {
            jcrSession.removeItem("/__properties" + tmpLocation + "/" + MARK + locale + "/" + name);
        }

        jcrSession.save();
    }

    /*
     * __acl can be part of versionable content and not versionable content.
     *
     * Versionable content are text and binary content.
     * Folders are not versionable in this first version due overhead:
     *  - We can have an exponential O(n^n) rate if we need to check for a leaf all tree.
     *  - We can loose target of versioning, that is to have several copies of a specific content.
     *
     * So in the implementation, only we will check-out/check-in __acl content where we are in a versionable content.
     *
     */
    public void createContentACE(String location, String name, Principal.PrincipalType principal, ACE.PermissionType permission)
            throws RepositoryException {

        String tmpLocation = ("/".equals(location)?"":location);
        String contentId = "/__acl" + tmpLocation;

        String acl = null;
        Node n;
        if (!jcrSession.itemExists(contentId)) {
            jcrCreatePath(contentId);
            n = jcrSession.getNode(contentId);
            n.addMixin("mix:title");
        }
        n = jcrSession.getNode(contentId);
        try {
            acl = n.getProperty("jcr:description").getString();
        } catch (PathNotFoundException ignored) {
        }

        acl = addAcl(acl, name, principal, permission);
        if (acl != null)
            n.setProperty("jcr:description", acl);

        jcrSession.save();

    }

    private String addAcl(String acl, String name, Principal.PrincipalType principal, ACE.PermissionType permission) {
        String newAce = "" + name + ":";
        if (principal == Principal.PrincipalType.USER)
            newAce += "USER:";
        if (principal == Principal.PrincipalType.GROUP)
            newAce += "GROUP:";
        if (permission == ACE.PermissionType.NONE)
            newAce += "NONE";
        if (permission == ACE.PermissionType.ALL)
            newAce += "ALL";
        if (permission == ACE.PermissionType.COMMENTS)
            newAce += "COMMENTS";
        if (permission == ACE.PermissionType.READ)
            newAce += "READ";
        if (permission == ACE.PermissionType.WRITE)
            newAce += "WRITE";

        // Check if newAce is in Acl
        if (acl == null || "".equals(acl))
            return newAce;
        if (acl.contains(newAce))
            return acl;
        acl += "," + newAce;
        return acl;
    }

    private String removeAcl(String acl, String name) {
        if (acl == null)
            return null;
        if ("".equals(acl))
            return "";

        String tempAcl = "";
        String[] aces = acl.split(",");
        for (String ace : aces) {
            String _user = ace.split(":")[0];
            if (!_user.equals(name)) {
                if ("".equals(tempAcl))
                    tempAcl += ace;
                else
                    tempAcl += ace + ",";
            }
        }

        return tempAcl;
    }

    public void deleteContentACE(String location, String name) throws RepositoryException {

        String acl = null;
        String tmpLocation = ("/".equals(location)?"":location);
        if (!jcrSession.itemExists("/acl" + tmpLocation))
            return;

        Node n = jcrSession.getNode("/__acl" + tmpLocation);

        if (n.getProperty("jcr:description") != null)
            acl = n.getProperty("jcr:description").getString();

        acl = removeAcl(acl, name);
        if (acl != null)
            n.setProperty("jcr:description", acl);

        jcrSession.save();
    }

    // JCR Aux methods
    public Value jcrValue(String content) throws RepositoryException {
        return jcrSession.getValueFactory().createValue(content);
    }

    public String jcrVersion(String location) {
        try {
            Version v = jcrSession.getWorkspace().getVersionManager().getBaseVersion(location);
            return v.getName();
        } catch (Exception e) {
            log.error("Unexpected error getting version history of " + location + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public String jcrVersion(Node n) {
        try {
            return jcrVersion(n.getPath());
        } catch (Exception e) {
            log.error("Unexpected error getting version history of " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public Date jcrCreated(String location) {
        try {
            return jcrSession.getNode(location).getProperty("jcr:created").getDate().getTime();
        } catch (Exception e) {
            log.error("Unexpected error getting jcr:created for " + location + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public Date jcrCreated(Node n) {
        try {
            return n.getProperty("jcr:created").getDate().getTime();
        } catch (Exception e) {
            log.error("Unexpected error getting jcr:created for " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public Date jcrLastModified(String location) {
        try {
            return jcrSession.getNode(location).getProperty("jcr:lastModified").getDate().getTime();
        } catch (Exception e) {
            log.error("Unexpected error getting jcr:lastModified for " + location + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public Date jcrLastModified(Node n) {
        try {
            return n.getProperty("jcr:lastModified").getDate().getTime();
        } catch (Exception e) {
            log.error("Unexpected error getting jcr:lastModified for " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    /*
     * All ACL will be storage under /__acl as a non versionable tree
     */
    public ACL jcrACL(String location) throws RepositoryException {
        // Create ACL from location
        ACL acl = null;
        // Check if we are in the root node or child node
        Node n = null;
        String tmpLocation = ("/".equals(location)?"":location);
        if (jcrSession.itemExists("/__acl" + tmpLocation))
            n = jcrSession.getNode("/__acl" + tmpLocation);
        else
            return null;

        Property _acl = null;
        try {
            _acl = n.getProperty("jcr:description");
        } catch (PathNotFoundException expected) {
            // Case where I have a left with ACL and some nodes without ACL
            // It should look into the parent
        }
        if (_acl == null) return null;
        String __acl = _acl.getString();
        acl = factory.parseACL(location, "ACL for " + location, __acl);
        return acl;
    }

    private Node jcrCreatePath(String absPath) throws RepositoryException {

        if (absPath == null) return null;
        if ("".equals(absPath)) return null;
        Node root = jcrSession.getRootNode();
        String[] relPath = absPath.split("/");
        // In a absPath we will start with 1 to about an empty first element
        for (int i=1; i<relPath.length; i++) {
            if (!root.hasNode(relPath[i])) {
                root = root.addNode(relPath[i], "nt:folder");
            } else
                root = root.getNode(relPath[i]);
        }
        return root;
    }

    /*
     * Delete all versions under absPath
     */
    // TODO Re-write this method in modeshape 3.2 once versioning issue is fixed
    @SuppressWarnings("unused")
    private void jcrDeleteVersions(String absPath) throws RepositoryException {

        if (absPath == null) return;
        if ("".equals(absPath)) return;

        // We delete with a bottom - up strategy
        Node n = jcrSession.getNode(absPath);

        // Iterating in children
        NodeIterator nIt = n.getNodes();
        while (nIt.hasNext()) {
            Node nC = nIt.nextNode();
            jcrDeleteVersions(nC.getPath());
        }

        // Working with node
        NodeType[] nT = n.getMixinNodeTypes();
        boolean versionable = false;
        for (NodeType t : nT) {
            if (t.getName().equals("mix:versionable")) {
                versionable = true;
                break;
            }
        }

        if (versionable) {
            // Checking if it has versions
            VersionHistory vh = null;
            try {
                vh = vm.getVersionHistory(absPath);
            } catch (Exception expected) {

            }

            // Delete child
            try {
                jcrSession.removeItem(absPath);
                jcrSession.save();
                jcrSession.refresh(true);
            } catch (Exception e) {
               // I can not remove a versionable node
            }

            if (vh != null) {
                VersionIterator vIt = vh.getAllLinearVersions();
                while (vIt.hasNext()) {
                    Version v = vIt.nextVersion();
                    if (!"jcr:rootVersion".equals(v.getName())) vh.removeVersion(v.getName());
                }
            }

        }
    }

    public PublishStatus jcrPublishStatus(String location) {
        // TODO to complete
        if (location == null)
            return null;

        return null;
    }

    public PublishStatus jcrPublishStatus(Node n) {
        // TODO to complete
        if (n == null)
            return null;
        return null;
    }

    public List<Principal> jcrPublishingRoles(String location) {
        // TODO to complete
        if (location == null)
            return null;
        return null;
    }

    public List<Principal> jcrPublishingRoles(Node n) {
        // TODO to complete
        if (n == null)
            return null;
        return null;
    }

    public String jcrCreatedBy(String location) {
        try {
            Node n = jcrSession.getNode(location);
            return n.getProperty("jcr:createdBy").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:createdBy user for location " + location + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public String jcrCreatedBy(Node n) {
        try {
            return n.getProperty("jcr:createdBy").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:createdBy user for location " + n.toString() + ". Msg: "
                    + e.getMessage());
            return null;
        }
    }

    public String jcrLastModifiedBy(String location) {
        try {
            Node n = jcrSession.getNode(location);
            return n.getProperty("jcr:lastModifiedBy").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:lastModifiedBy user for location " + location + ". Msg: "
                    + e.getMessage());
            return null;
        }
    }

    public String jcrLastModifiedBy(Node n) {
        try {
            return n.getProperty("jcr:lastModifiedBy").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:lastModifiedBy user for location " + n.toString() + ". Msg: "
                    + e.getMessage());
            return null;
        }
    }

    public String jcrEncoding(Node n) {
        try {
            return n.getProperty("jcr:encoding").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:encoding user for location " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public String jcrTextContent(Node n) {
        try {
            return n.getNode("jcr:content").getProperty("jcr:data").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:data user for location " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public String jcrContentType(Node n) {
        try {
            return n.getProperty("jcr:mimeType").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:mimeType user for location " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public InputStream jcrContent(Node n) {
        try {
            return n.getNode("jcr:content").getProperty("jcr:data").getBinary().getStream();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:content user for location " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public byte[] jcrContentData(Node n) {
        try {
            return toByteArray(n.getNode("jcr:content").getProperty("jcr:data").getBinary().getStream());
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:content user for location " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public String jcrDescription(Node n) {
        try {
            return n.getProperty("jcr:description").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:description user for location " + n.toString() + ". Msg: "
                    + e.getMessage());
            return null;
        }
    }

    public String jcrTitle(Node n) {
        try {
            return n.getProperty("jcr:title").getString();
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving jcr:title user for location " + n.toString() + ". Msg: " + e.getMessage());
            return null;
        }
    }

    public String jcrCategoryDescription(String location, String locale) {
        try {
            Node n = jcrSession.getNode(location + "/" + MARK + locale);
            return jcrDescription(n);
        } catch (RepositoryException e) {
            log.error("Unexpected error retrieving categories node for location " + location + ". Msg: " + e.getMessage());
        }
        return null;
    }

    public String[] jcrChildCategories(String location) throws RepositoryException {
        ArrayList<String> childLocations = new ArrayList<String>();

        Node n = jcrSession.getNode(location);
        NodeIterator ni = n.getNodes();
        while (ni.hasNext()) {
            Node child = ni.nextNode();
            String name = child.getName();
            if (!name.startsWith("__"))
                childLocations.add(child.getPath());
        }
        if (childLocations.size() == 0)
            return null;
        else {
            String[] output = new String[childLocations.size()];
            output = childLocations.toArray(output);
            return output;
        }
    }

    public void jcrCategoryReference(String contentLocation, String categoryLocation) throws RepositoryException {
        String id = contentLocation.substring(contentLocation.lastIndexOf("/") + 1);
        jcrSession.getWorkspace().clone(jcrSession.getWorkspace().getName(), contentLocation,
                "/__categories" + categoryLocation + "/__references/" + id, false);
    }

    public void jcrUpdateDescriptionPath(Node n) throws RepositoryException {
        if (n == null)
            return;
        if (n.getProperty("jcr:description") != null) {
            String type = n.getProperty("jcr:description").getString().split(":")[0];
            if ("textcontent".equals(type) || "binarycontent".equals(type) || "folder".equals(type)) {
                n.setProperty("jcr:description", type + ":" + n.getPath());
                jcrSession.save();
                NodeIterator ni = n.getNodes();
                while (ni.hasNext()) {
                    Node child = ni.nextNode();
                    jcrUpdateDescriptionPath(child);
                }
            }
        }
    }

    public List<String> jcrLocales(Node n) throws RepositoryException {
        if (n == null)
            return null;
        NodeIterator ni = n.getNodes();
        List<String> locales = new ArrayList<String>();
        while (ni.hasNext()) {
            Node node = ni.nextNode();
            String name = node.getName();
            if (!WcmConstants.RESERVED_ENTRIES.contains(name) && name.startsWith("__")) {
                locales.add(name.substring(2));
            }
        }
        if (locales.size() > 0)
            return locales;
        else
            return null;
    }

    public List<String> jcrLocalesProperties(Node n) throws RepositoryException {
        if (n == null)
            return null;
        if (!jcrSession.itemExists("/__properties" + n.getPath()))
            return null;
        n = jcrSession.getNode("/__properties" + n.getPath());

        NodeIterator ni = n.getNodes();
        List<String> locales = new ArrayList<String>();
        while (ni.hasNext()) {
            Node node = ni.nextNode();
            String name = node.getName();
            if (!WcmConstants.RESERVED_ENTRIES.contains(name) && name.startsWith("__")) {
                locales.add(name.substring(2));
            }
        }
        if (locales.size() > 0)
            return locales;
        else
            return null;
    }

    // Aux methods
    public String parent(String location) {

        if (location == null)
            return null;

        if ("/".equals(location))
            return location;

        String[] locs = location.split("/");

        if (locs.length > 2) {
            StringBuffer sb = new StringBuffer(location.length());
            for (int i = 1; i < (locs.length - 1); i++) {
                sb.append("/" + locs[i]);
            }
            return sb.toString();
        } else {
            return "/";
        }
    }

    public byte[] toByteArray(InputStream is) {
        try {

            byte[] data = new byte[16000];
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        } catch (Exception e) {
            log.error("Error creating createBinaryContent() transforming toByteArray(). Msg: " + e.getMessage());
        }
        return null;
    }

}
