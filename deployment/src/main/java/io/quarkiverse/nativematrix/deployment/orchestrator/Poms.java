package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Minimal reading of {@code pom.xml} files: the {@code <parent>} element.
 */
final class Poms {

    /**
     * @param relativePath the {@code relativePath} of the parent; empty when the POM declares an empty element, which
     *        means "resolve from repositories only"
     */
    record Parent(String groupId, String artifactId, String version, Optional<String> relativePath) {
    }

    private Poms() {
    }

    static Optional<Parent> parent(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            Element parent = child(project, "parent");
            if (parent == null) {
                return Optional.empty();
            }
            Element relativePath = child(parent, "relativePath");
            Optional<String> relative = relativePath == null ? Optional.of("../pom.xml")
                    : Optional.of(relativePath.getTextContent().trim()).filter(s -> !s.isEmpty());
            return Optional.of(new Parent(text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"),
                    relative));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * @return the artifactId declared by a POM, or null
     */
    static String artifactId(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return text(factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement(), "artifactId");
        } catch (Exception e) {
            return null;
        }
    }

    static boolean exists(Path pom) {
        return Files.isRegularFile(pom);
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName() != null ? e.getLocalName() : e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? "" : e.getTextContent().trim();
    }
}
