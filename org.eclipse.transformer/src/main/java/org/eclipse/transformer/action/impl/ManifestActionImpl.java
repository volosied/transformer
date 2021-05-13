/********************************************************************************
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: (EPL-2.0 OR Apache-2.0)
 ********************************************************************************/

package org.eclipse.transformer.action.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.eclipse.transformer.TransformException;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.BundleData;
import org.eclipse.transformer.util.ByteData;
import org.eclipse.transformer.util.ManifestWriter;
import org.slf4j.Logger;

import aQute.bnd.header.Attrs;
import aQute.bnd.header.OSGiHeader;
import aQute.bnd.header.Parameters;
import aQute.lib.io.ByteBufferOutputStream;

public class ManifestActionImpl extends ActionImpl {
	public static final String	META_INF				= "META-INF/";
	public static final String	MANIFEST_MF				= "MANIFEST.MF";
	public static final String	META_INF_MANIFEST_MF	= "META-INF/MANIFEST.MF";

	//

	public static final boolean	IS_MANIFEST				= true;
	public static final boolean	IS_FEATURE				= !IS_MANIFEST;

	public static ManifestActionImpl newManifestAction(Logger logger, boolean isTerse, boolean isVerbose,
		InputBufferImpl buffer, SelectionRuleImpl selectionRule, SignatureRuleImpl signatureRule) {

		return new ManifestActionImpl(logger, isTerse, isVerbose, buffer, selectionRule, signatureRule, IS_MANIFEST);
	}

	public static ManifestActionImpl newFeatureAction(Logger logger, boolean isTerse, boolean isVerbose,
		InputBufferImpl buffer, SelectionRuleImpl selectionRule, SignatureRuleImpl signatureRule) {

		return new ManifestActionImpl(logger, isTerse, isVerbose, buffer, selectionRule, signatureRule, IS_FEATURE);
	}

	public ManifestActionImpl(Logger logger, boolean isTerse, boolean isVerbose, InputBufferImpl buffer,
		SelectionRuleImpl selectionRule, SignatureRuleImpl signatureRule, boolean isManifest) {

		super(logger, isTerse, isVerbose, buffer, selectionRule, signatureRule);

		this.isManifest = isManifest;
	}

	//

	@Override
	public String getName() {
		return (getIsManifest() ? "Manifest Action" : "Feature Action");
	}

	@Override
	public ActionType getActionType() {
		return (getIsManifest() ? ActionType.MANIFEST : ActionType.FEATURE);
	}

	//

	private final boolean isManifest;

	public boolean getIsManifest() {
		return isManifest;
	}

	public boolean getIsFeature() {
		return !isManifest;
	}

	@Override
	public String getAcceptExtension() {
		return (getIsManifest() ? "manifest.mf" : ".mf");
	}

	//

	@Override
	public ByteData apply(String initialName, byte[] initialBytes, int initialCount) throws TransformException {

		String className = getClass().getSimpleName();
		String methodName = "apply";

		debug("[ {}.{} ]: [ {} ] Initial bytes [ {} ]", className, methodName, initialName, initialCount);

		setResourceNames(initialName, initialName);

		ByteData initialData = new ByteData(initialName, initialBytes, 0, initialCount);

		Manifest initialManifest;
		try {
			initialManifest = new Manifest(initialData.asStream());
		} catch (IOException e) {
			error("Failed to parse manifest [ {} ]", e, initialName);
			return null;
		}

		Manifest finalManifest = new Manifest();

		transform(initialName, initialManifest, finalManifest);

		// info("[ {}.{} ]: [ {} ] Replacements [ {} ]",
		// getClass().getSimpleName(), "transform",
		// initialName, getActiveChanges().getReplacements());

		if (!hasNonResourceNameChanges()) {
			debug("[ {}.{} ]: [ {} ] Null transform", className, methodName, initialName);
			return null;
		}

		ByteBufferOutputStream outputStream = new ByteBufferOutputStream(initialCount);
		try {
			write(finalManifest, outputStream); // throws IOException
		} catch (IOException e) {
			error("Failed to write manifest [ {} ]", e, initialName);
			return null;
		}

		byte[] finalBytes = outputStream.toByteArray();
		debug("[ {}.{} ]: [ {} ] Active transform; final bytes [ {} ]", className, methodName, initialName,
			finalBytes.length);

		return new ByteData(initialName, finalBytes);
	}

	protected void transform(String inputName, Manifest initialManifest, Manifest finalManifest) {
		Attributes initialMainAttributes = initialManifest.getMainAttributes();
		Attributes finalMainAttributes = finalManifest.getMainAttributes();

		addReplacements(transformPackages(inputName, "main", initialMainAttributes, finalMainAttributes));

		if (transformBundleIdentity(inputName, initialMainAttributes, finalMainAttributes)) {
			addReplacement();
		}

		Map<String, Attributes> initialEntries = initialManifest.getEntries();
		Map<String, Attributes> finalEntries = finalManifest.getEntries();

		for (Map.Entry<String, Attributes> entry : initialEntries.entrySet()) {
			String entryKey = entry.getKey();
			Attributes initialEntryAttributes = entry.getValue();

			Attributes finalAttributes = new Attributes(initialEntryAttributes.size());
			finalEntries.put(entryKey, finalAttributes);

			addReplacements(transformPackages(inputName, entryKey, initialEntryAttributes, finalAttributes));
		}
	}

	private static final Set<String> SELECT_ATTRIBUTES;

	static {
		Set<String> useNames = new HashSet<>();
		useNames.add("DynamicImport-Package");
		useNames.add("Export-Package");
		useNames.add("Import-Package");
		useNames.add("Subsystem-Content");
		useNames.add("IBM-API-Package");
		useNames.add("Provide-Capability");
		useNames.add("Require-Capability");
		SELECT_ATTRIBUTES = useNames;
	}

	protected boolean selectAttribute(String name) {
		return SELECT_ATTRIBUTES.contains(name);
	}

	protected int transformPackages(String inputName, String entryName, Attributes initialAttributes,
		Attributes finalAttributes) {

		debug("Transforming [ {} ]: [ {} ] Attributes [ {} ]", inputName, entryName, initialAttributes.size());

		int replacements = 0;

		for (Map.Entry<Object, Object> entries : initialAttributes.entrySet()) {
			Object untypedName = entries.getKey();
			String typedName = untypedName.toString();

			String initialValue = (String) entries.getValue();
			String finalValue = null;

			if (selectAttribute(typedName)) {
				finalValue = replacePackages(initialValue);
			}

			if (finalValue == null) {
				finalValue = initialValue;
			} else {
				replacements++;
			}

			finalAttributes.put(untypedName, finalValue);
		}

		debug("Transformed [ {} ]: [ {} ] Attributes [ {} ] Replacements [ {} ]", inputName, entryName,
			finalAttributes.size(), replacements);

		return replacements;
	}

	protected void write(Manifest manifest, OutputStream outputStream) throws IOException {
		if (getIsManifest()) {
			writeAsManifest(manifest, outputStream); // throws IOException
		} else {
			writeAsFeature(manifest, outputStream); // throws IOException
		}
	}

	protected void writeAsManifest(Manifest manifest, OutputStream outputStream) throws IOException {
		// manifest.write(outputStream); // throws IOException
		ManifestWriter.write(manifest, outputStream);
	}

	// Copied and updated from:
	// https://github.com/OpenLiberty/open-liberty/blob/integration/
	// dev/wlp-featureTasks/src/com/ibm/ws/wlp/feature/tasks/FeatureBuilder.java

	@SuppressWarnings("unused")
	protected void writeAsFeature(Manifest manifest, OutputStream outputStream) throws IOException {
		PrintWriter writer = new PrintWriter(outputStream);

		StringBuilder builder = new StringBuilder();

		for (Map.Entry<Object, Object> mainEntry : manifest.getMainAttributes()
			.entrySet()) {
			writer.append(mainEntry.getKey()
				.toString());
			writer.append(": ");

			String value = (String) mainEntry.getValue();
			if (value.indexOf(',') == -1) {
				writer.append(value);

			} else {
				Parameters parms = OSGiHeader.parseHeader(value);

				boolean continuedLine = false;
				for (Map.Entry<String, Attrs> parmEntry : parms.entrySet()) {
					if (continuedLine) {
						writer.append(",\r ");
					}

					// bnd might have added ~ characters if there are duplicates
					// in
					// the source, so we should remove them before we output it
					// so we
					// get back to the original intended content.

					String parmName = parmEntry.getKey();
					int index = parmName.indexOf('~');
					if (index != -1) {
						parmName = parmName.substring(0, index);
					}
					writer.append(parmName);

					Attrs parmAttrs = parmEntry.getValue();
					for (Map.Entry<String, String> parmAttrEntry : parmAttrs.entrySet()) {
						String parmAttrName = parmAttrEntry.getKey();
						String parmAttrValue = quote(builder, parmAttrEntry.getValue());

						writer.append("; ");
						writer.append(parmAttrName);
						writer.append('=');
						writer.append(parmAttrValue);
					}

					continuedLine = true;
				}
			}

			writer.append("\r");
		}

		writer.flush();
	}

	public String quote(StringBuilder sb, String value) {
		@SuppressWarnings("unused")
		boolean isClean = OSGiHeader.quote(sb, value);
		String quotedValue = sb.toString();
		sb.setLength(0);
		return quotedValue;
	}

	/**
	 * Replace all embedded packages of specified text with replacement
	 * packages.
	 *
	 * @param text Text embedding zero, one, or more package names.
	 * @return The text with all embedded package names replaced. Null if no
	 *         replacements were performed.
	 */
	protected String replacePackages(String text) {

		// System.out.println("Initial text [ " + text + " ]");

		String initialText = text;

		for (Map.Entry<String, String> renameEntry : getPackageRenames().entrySet()) {
			String key = renameEntry.getKey();
			int keyLen = key.length();

			boolean matchSubpackages = SignatureRuleImpl.containsWildcard(key);
			if (matchSubpackages) {
				key = SignatureRuleImpl.stripWildcard(key);
			}

			// System.out.println("Next target [ " + key + " ]");

			int textLimit = text.length() - keyLen;

			int lastMatchEnd = 0;
			while (lastMatchEnd <= textLimit) {
				int matchStart = text.indexOf(key, lastMatchEnd);
				if (matchStart == -1) {
					break;
				}

				if (!SignatureRuleImpl.isTruePackageMatch(text, matchStart, keyLen, matchSubpackages)) {
					lastMatchEnd = matchStart + keyLen;
					continue;
				}

				String value = renameEntry.getValue();
				Boolean shouldRemove = false;
				if(value.equals("<remove>")){
					value="";
					shouldRemove = true;
				}
				int valueLen = value.length();

				String head = text.substring(0, matchStart);
				String tail = text.substring(matchStart + keyLen);

				int tailLenBeforeReplaceVersion = tail.length();

				String newVersion = getPackageVersions().get(value);
				if(shouldRemove){
					tail = removePackageVersion(tail);
				} else if (newVersion != null) {
					tail = replacePackageVersion(tail, newVersion);
				} else {
					debug("replacePackages [ {} ]: [ {} -> {} ]; leaving version", initialText, key, value);
				}

				text = head + value + tail;
				int tailLenAfterReplaceVersion = tail.length();

				lastMatchEnd = matchStart + valueLen;

				// Replacing the key or the version can increase or decrease the
				// text length.
				textLimit += (valueLen - keyLen);
				textLimit += (tailLenAfterReplaceVersion - tailLenBeforeReplaceVersion);

				// System.out.println("Next text [ " + text + " ]");
			}
		}

		if (initialText == text) {
			// System.out.println("Final text is unchanged");
			return null;
		} else {
			// System.out.println("Final text [ " + text + " ]");
			return text;
		}
	}

	protected String removePackageVersion(String text) {
		// debug("replacePackageVersion: ( {} )", text );

		String packageText = getPackageAttributeText(text);

		if (packageText == null) {
			return text;
		} else if (packageText.isEmpty()) {
			return text;
		}

		debug("removePackageVersion for {}", packageText);
		return text.substring(packageText.length(), text.length());

	}

	// DynamicImport-Package: com.ibm.websphere.monitor.meters;version="1.0.0
	// ",com.ibm.websphere.monitor.jmx;version="1.0.0",com.ibm.ws.jsp.webcon
	// tainerext,com.ibm.wsspi.request.probe.bci,com.ibm.wsspi.probeExtensio
	// n,com.ibm.ws.webcontainer.monitor

	// Import-Package: javax.servlet;version="[2.6,3)",javax.servlet.annotati
	// on;version="[2.6,3)",javax.servlet.descriptor;version="[2.6,3)",javax
	// .servlet.http;version="[2.6,3)",com.ibm.wsspi.http;version="[2.0,3)",
	// com.ibm.ws.javaee.dd;version="1.0",com.ibm.ws.javaee.dd.common;versio
	// n="1.0",com.ibm.ws.javaee.dd.common.wsclient;version="1.0",com.ibm.ws
	// .javaee.dd.web;version="1.0",com.ibm.ws.javaee.dd.web.common;version=
	// "1.0",com.ibm.ws.util;version="[1.0,2)",com.ibm.wsspi.injectionengine
	// ;version="[3.0,4)",com.ibm.ws.runtime.metadata;version="[1.1,2)"

	/**
	 * Answer package attribute text which has been updated with a new version
	 * range. Examples
	 *
	 * <pre>
	 * Import-Package: javax.servlet;version="[2.6,3)",javax.servlet.annotation;version="[2.6,3)"
	 * </pre>
	 *
	 * <pre>
	 * DynamicImport-Package: com.ibm.websphere.monitor.meters;version="1.0.0",com.ibm.websphere.monitor.jmx;version="1.0.0"
	 * </pre>
	 *
	 * The leading package name must be removed from the attribute text. Other
	 * package names and attributes may be present. Attribute text for different
	 * packages use commas as separators, except, commas inside quotation marks
	 * are not separators. This is important because commas are present in
	 * version ranges.
	 *
	 * @param text Package attribute text.
	 * @param newVersion Replacement version values for the package attribute.
	 * @return String with version numbers of first package replaced by the
	 *         newVersion.
	 */
	protected String replacePackageVersion(String text, String newVersion) {
		// debug("replacePackageVersion: ( {} )", text );

		String packageText = getPackageAttributeText(text);

		if (packageText == null) {
			return text;
		} else if (packageText.isEmpty()) {
			return text;
		}

		// debug("replacePackageVersion: (packageText: {} )", packageText);

		final String VERSION = "version";
		final int VERSION_LEN = 7;
		final char QUOTE_MARK = '\"';

		int versionIndex = packageText.indexOf(VERSION);
		if (versionIndex == -1) {
			return text; // nothing to replace
		}

		// The actual version numbers are after the "version" and the "=" and
		// between quotation marks ("").
		// Ignore white space that occurs around the "=", but do not ignore
		// white space between quotation marks.
		// Everything inside the "" is part of the version and will be replaced.
		boolean foundEquals = false;
		boolean foundQuotationMark = false;
		int versionBeginIndex = -1;
		int versionEndIndex = -1;

		// skip to actual version number which is after "=". Version begins
		// inside double quotation marks
		for (int i = versionIndex + VERSION_LEN; i < packageText.length(); i++) {
			char ch = packageText.charAt(i);

			// skip white space until we find equals sign
			if (!foundEquals) {
				if (ch == '=') {
					foundEquals = true;
					continue;
				}

				if (Character.isWhitespace(ch)) {
					continue;
				}
				error("Syntax error found non-white-space character before equals sign in version [{}]", packageText);
				return text; // Syntax error - returning original text
			}

			// Skip white space past the equals sign
			if (Character.isWhitespace(ch)) {
				// debug("ch is \'{}\' and is whitespace.", ch);
				continue;
			}

			// When we find the quotation marks past the equals sign, we are
			// finished.
			if (!foundQuotationMark) {
				if (ch == QUOTE_MARK) {
					versionBeginIndex = i + 1; // just past the 1st quotation
												// mark

					versionEndIndex = packageText.indexOf('\"', i + 1);
					if (versionEndIndex == -1) {
						error("Syntax error, package version does not have closing quotation mark");
						return text; // Syntax error - returning original text
					}
					versionEndIndex--; // just before the 2nd quotation mark

					// debug("versionBeginIndex = [{}]", versionBeginIndex);
					// debug("versionEndIndex = [{}]", versionEndIndex);
					foundQuotationMark = true; // not necessary, just leave loop
					break;
				}

				if (Character.isWhitespace(ch)) {
					continue;
				}

				error("Syntax error found non-white-space character after equals sign  in version [{}]", packageText);
				return text; // Syntax error - returning original text
			}
		}

		// String oldVersion = packageText.substring(versionBeginIndex,
		// versionEndIndex+1);
		// debug("old version[{}] new version[{}]", oldVersion, newVersion);

		String head = text.substring(0, versionBeginIndex);
		String tail = text.substring(versionEndIndex + 1);

		String newText = head + newVersion + tail;
		// debug("Old [{}] New [{}]", text , newText);

		return newText;
	}

	//
	// Subsystem-Content: com.ibm.websphere.appserver.javax.el-3.0;
	// apiJar=false; type="osgi.subsystem.feature",
	// com.ibm.websphere.appserver.javax.servlet-3.1; ibm.tolerates:="4.0";
	// apiJar=false; type="osgi.subsystem.feature",
	// com.ibm.websphere.javaee.jsp.2.3; location:="dev/api/spec/,lib/";
	// mavenCoordinates="javax.servlet.jsp:javax.servlet.jsp-api:2.3.1";
	// version="[1.0.0,1.0.200)"

	/**
	 * @param text - A string containing package attribute text at the head of
	 *            the string. Assumptions: - The first package name has already
	 *            been stripped from the embedding text. - Other package names
	 *            and attributes may or may not follow. - Packages are separated
	 *            by a comma. - If a comma is inside quotation marks, it is not
	 *            a package delimiter.
	 * @return package attribute text
	 */
	protected String getPackageAttributeText(String text) {
		// debug("getPackageAttributeText ENTER[ text: {}]", text);

		if (text == null) {
			return null;
		}

		if (!firstCharIsSemicolon(text)) {
			return ""; // no package attributes
		}

		int commaIndex = text.indexOf(',');
		debug("Comma index: [{}]", commaIndex);
		// If there is no comma, then the whole text is the packageAttributeText
		if (commaIndex == -1) {
			return text;
		}

		// packageText is beginning of text up to and including comma.
		// Need to test whether the comma is within quotes - thus not the true
		// end of the packageText.
		// If an odd number of quotes are found, then the comma is in quotes and
		// we need to find the next comma.
		String packageText = text.substring(0, commaIndex + 1);
		debug("packageText [ {} ]", packageText);

		while (!isPackageDelimitingComma(text, packageText, commaIndex)) {
			commaIndex = text.indexOf(',', packageText.length());
			if (commaIndex == -1) {
				packageText = text; // No trailing comma indicates embedding
									// text is the package text.
				break;
			} else {
				packageText = text.substring(0, commaIndex + 1);
			}

			// If there is a syntax error (missing closing quotes) return what
			// we have
			if (!hasEvenNumberOfOccurrencesOfChar(text, '\"')) {
				break;
			}
		}

		debug("getPackageAttributeText returning: [ {} ]", packageText);
		return packageText;
	}

	/**
	 * Tell if the first non-white space character of the parameter is a
	 * semi-colon.
	 *
	 * @param s string
	 * @return true if first char is semi colon.
	 */
	protected boolean firstCharIsSemicolon(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) {
				continue;
			}
			if (s.charAt(i) == ';') {
				return true;
			}
			return false;
		}
		return false;
	}

	protected int indexOfNextNonWhiteSpaceChar(String s, int currentIndex) {
		for (int i = currentIndex; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) {
				continue;
			}
			return i;
		}
		return -1;
	}

	/**
	 * @param testString - The entire remaining unprocessed text of a
	 *            MANIFEST.MF attribute that immediately follows a package name
	 * @param packageText - Text that immediately follows a package name in a
	 *            MANIFEST.MF attribute
	 * @param indexOfComma
	 * @return
	 */
	private boolean isPackageDelimitingComma(String testString, String packageText, int indexOfComma) {

		int indexOfNextNonWhiteSpaceCharAfterComma = indexOfNextNonWhiteSpaceChar(testString, indexOfComma + 1);
		char characterAfterComma = testString.charAt(indexOfNextNonWhiteSpaceCharAfterComma);
		if (Character.isAlphabetic(characterAfterComma)) {
			if (!hasEvenNumberOfOccurrencesOfChar(packageText, '\"')) {
				return false;
			}
			return true;
		}

		return false;
	}

	private boolean hasEvenNumberOfOccurrencesOfChar(String testString, @SuppressWarnings("unused") char testChar) {
		long occurrences = testString.chars()
			.filter(ch -> ch == '\"')
			.count();
		return ((occurrences % 2) == 0);
	}

	//

	public static final String	SYMBOLIC_NAME_PROPERTY_NAME	= "Bundle-SymbolicName";
	public static final String	VERSION_PROPERTY_NAME		= "Bundle-Version";
	public static final String	NAME_PROPERTY_NAME			= "Bundle-Name";
	public static final String	DESCRIPTION_PROPERTY_NAME	= "Bundle-Description";

	// Bundle case:
	// Bundle updates:
	//
	// Updated:
	//
	// Bundle-Description: WAS WebContainer 8.1 with Servlet 4.0 support
	// Bundle-Name: WAS WebContainer
	// Bundle-SymbolicName: com.ibm.ws.webcontainer.servlet.4.0
	// Bundle-Version: 1.0.36.cl200120200108-0300
	//
	// Ignored:
	//
	// Bundle-Copyright: Copyright (c) 1999, 2019 IBM Corporation and others.
	// All rights reserved. This program and the accompanying materials are
	// made available under the terms of the Eclipse Public License v1.0 wh
	// ich accompanies this distribution, and is available at http://www.ecl
	// ipse.org/legal/epl-v10.html.
	// Bundle-License: Eclipse Public License; url=https://www.eclipse.org/le
	// gal/epl-v10.html
	// Bundle-ManifestVersion: 2
	// Bundle-SCM: connection=scm:git:https://github.com/OpenLiberty/open-lib
	// erty.git, developerConnection=scm:git:https://github.com:OpenLiberty/
	// open-liberty.git, url=https://github.com/OpenLiberty/open-liberty/tre
	// e/master
	// Bundle-Vendor: IBM

	// Subsystem case:
	//
	// Subsystem-Description: %description
	// Subsystem-License: https://www.eclipse.org/legal/epl-v10.html
	// Subsystem-Localization: OSGI-INF/l10n/com.ibm.websphere.appserver.jsp-2.3
	// Subsystem-ManifestVersion: 1
	// Subsystem-Name: JavaServer Pages 2.3
	// Subsystem-SymbolicName: com.ibm.websphere.appserver.jsp-2.3;
	// visibility:=public; singleton:=true
	// Subsystem-Type: osgi.subsystem.feature
	// Subsystem-Vendor: IBM Corp.
	// Subsystem-Version: 1.0.0

	public boolean transformBundleIdentity(String inputName, Attributes initialMainAttributes,
		Attributes finalMainAttributes) {

		String initialSymbolicName = initialMainAttributes.getValue(SYMBOLIC_NAME_PROPERTY_NAME);
		if (initialSymbolicName == null) {
			debug("Input [ {} ] has no bundle symbolic name", inputName);
			return false;
		}

		int indexOfSemiColon = initialSymbolicName.indexOf(';');
		String symbolicNamesAttributes = null;
		if (indexOfSemiColon != -1) {
			symbolicNamesAttributes = initialSymbolicName.substring(indexOfSemiColon);
			initialSymbolicName = initialSymbolicName.substring(0, indexOfSemiColon);
		}

		String matchCase;
		boolean matched;
		boolean isWildcard;
		BundleData bundleUpdate = getBundleUpdate(initialSymbolicName);
		if (bundleUpdate == null) {
			bundleUpdate = getBundleUpdate("*");
			if (bundleUpdate != null) {
				matched = true;
				isWildcard = true;
				matchCase = "a wildcard identity update";
			} else {
				matched = false;
				isWildcard = false;
				matchCase = "no identity update";
			}
		} else {
			matched = true;
			isWildcard = false;
			matchCase = "identity update";
		}
		debug("Input [ {} ] symbolic name [ {} ] has {}", inputName, initialSymbolicName, matchCase);
		if (!matched) {
			return false;
		}

		@SuppressWarnings("null")
		String finalSymbolicName = bundleUpdate.getSymbolicName();

		if (isWildcard) {
			int wildcardOffset = finalSymbolicName.indexOf('*');
			if (wildcardOffset != -1) {
				finalSymbolicName = finalSymbolicName.substring(0, wildcardOffset) + initialSymbolicName
					+ finalSymbolicName.substring(wildcardOffset + 1);
			}
		}

		if (symbolicNamesAttributes != null) {
			finalSymbolicName += symbolicNamesAttributes;
		}

		finalMainAttributes.putValue(SYMBOLIC_NAME_PROPERTY_NAME, finalSymbolicName);
		verbose("Bundle symbolic name: {} --> {}", initialSymbolicName, finalSymbolicName);

		if (!isWildcard) {
			String initialVersion = initialMainAttributes.getValue(VERSION_PROPERTY_NAME);
			if (initialVersion != null) {
				String finalVersion = bundleUpdate.getVersion();
				if ((finalVersion != null) && !finalVersion.isEmpty()) {
					finalMainAttributes.putValue(VERSION_PROPERTY_NAME, finalVersion);
					verbose("Bundle version: {} --> {}", initialVersion, finalVersion);
				}
			}
		}

		String initialName = initialMainAttributes.getValue(NAME_PROPERTY_NAME);
		if (initialName != null) {
			String finalName = bundleUpdate.updateName(initialName);
			if ((finalName != null) && !finalName.isEmpty()) {
				finalMainAttributes.putValue(NAME_PROPERTY_NAME, finalName);
				verbose("Bundle name: {} --> {}", initialName, finalName);
			}
		}

		String initialDescription = initialMainAttributes.getValue(DESCRIPTION_PROPERTY_NAME);
		if (initialDescription != null) {
			String finalDescription = bundleUpdate.updateDescription(initialDescription);
			if ((finalDescription != null) && !finalDescription.isEmpty()) {
				finalMainAttributes.putValue(DESCRIPTION_PROPERTY_NAME, finalDescription);
				verbose("Bundle description: {} --> {}", initialDescription, finalDescription);
			}
		}

		return true;
	}
}
