/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
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
 */

package com.liferay.portal.servlet.filters.dynamiccss;

import com.liferay.portal.kernel.io.unsync.UnsyncByteArrayOutputStream;
import com.liferay.portal.kernel.io.unsync.UnsyncPrintWriter;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.JavaConstants;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.SessionParamUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.UnsyncPrintWriterPool;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.model.PortletConstants;
import com.liferay.portal.model.Theme;
import com.liferay.portal.scripting.ruby.RubyExecutor;
import com.liferay.portal.service.ThemeLocalServiceUtil;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.tools.SassToCssBuilder;
import com.liferay.portal.util.ClassLoaderUtil;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PropsValues;

import java.io.File;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.time.StopWatch;

/**
 * @author Raymond Augé
 * @author Sergio Sánchez
 */
public class DynamicCSSUtil {

	public static void init() {
		try {
			_rubyScript = StringUtil.read(
				ClassLoaderUtil.getPortalClassLoader(),
				"com/liferay/portal/servlet/filters/dynamiccss/main.rb");
		}
		catch (Exception e) {
			_log.error(e, e);
		}
	}

	public static String parseSass(
			ServletContext servletContext, HttpServletRequest request,
			String resourcePath, String content)
		throws Exception {

		if (!DynamicCSSFilter.ENABLED) {
			return content;
		}

		StopWatch stopWatch = null;

		if (_log.isDebugEnabled()) {
			stopWatch = new StopWatch();

			stopWatch.start();
		}

		// Request will only be null when called by StripFilterTest

		if (request == null) {
			return content;
		}

		ThemeDisplay themeDisplay = (ThemeDisplay)request.getAttribute(
			WebKeys.THEME_DISPLAY);

		Theme theme = null;

		if (themeDisplay == null) {
			theme = _getTheme(request);

			if (theme == null) {
				String currentURL = PortalUtil.getCurrentURL(request);

				if (_log.isWarnEnabled()) {
					_log.warn("No theme found for " + currentURL);
				}

				return content;
			}
		}

		String parsedContent = null;

		boolean themeCssFastLoad = _isThemeCssFastLoad(request, themeDisplay);

		URLConnection resourceURLConnection = null;

		URL resourceURL = servletContext.getResource(resourcePath);

		if (resourceURL != null) {
			resourceURLConnection = resourceURL.openConnection();
		}

		URLConnection cacheResourceURLConnection = null;

		URL cacheResourceURL = _getCacheResource(servletContext, resourcePath);

		if (cacheResourceURL != null) {
			cacheResourceURLConnection = cacheResourceURL.openConnection();
		}

		if (themeCssFastLoad && (cacheResourceURLConnection != null) &&
			(resourceURLConnection != null) &&
			(cacheResourceURLConnection.getLastModified() ==
				resourceURLConnection.getLastModified())) {

			parsedContent = StringUtil.read(
				cacheResourceURLConnection.getInputStream());

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Loading SASS cache from " + cacheResourceURL.getPath() +
						" takes " + stopWatch.getTime() + " ms");
			}
		}
		else {
			content = SassToCssBuilder.parseStaticTokens(content);

			String queryString = request.getQueryString();

			if (!themeCssFastLoad && Validator.isNotNull(queryString)) {
				content = propagateQueryString(content, queryString);
			}

			parsedContent = _parseSass(
				servletContext, request, themeDisplay, theme, resourcePath,
				content);

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Parsing SASS for " + resourcePath + " takes " +
						stopWatch.getTime() + " ms");
			}
		}

		if (Validator.isNull(parsedContent)) {
			return content;
		}

		parsedContent = StringUtil.replace(
			parsedContent,
			new String[] {
				"@portal_ctx@", "@theme_image_path@"
			},
			new String[] {
				PortalUtil.getPathContext(),
				_getThemeImagesPath(request, themeDisplay, theme)
			});

		return parsedContent;
	}

	private static URL _getCacheResource(
			ServletContext servletContext, String resourcePath)
		throws Exception {

		int pos = resourcePath.lastIndexOf(StringPool.SLASH);

		String cacheFileName =
			resourcePath.substring(0, pos + 1) + ".sass-cache/" +
				resourcePath.substring(pos + 1);

		return servletContext.getResource(cacheFileName);
	}

	private static String _getCssThemePath(
			HttpServletRequest request, ThemeDisplay themeDisplay, Theme theme)
		throws Exception {

		String cssThemePath = null;

		if (themeDisplay != null) {
			cssThemePath = themeDisplay.getPathThemeCss();
		}
		else {
			String cdnHost = StringPool.BLANK;

			if (PortalUtil.isCDNDynamicResourcesEnabled(request)) {
				cdnHost = PortalUtil.getCDNHost(request);
			}

			String themeStaticResourcePath = theme.getStaticResourcePath();

			cssThemePath =
				cdnHost + themeStaticResourcePath + theme.getCssPath();
		}

		return cssThemePath;
	}

	private static File _getSassTempDir(ServletContext servletContext) {
		File sassTempDir = (File)servletContext.getAttribute(_SASS_DIR_KEY);

		if (sassTempDir != null) {
			return sassTempDir;
		}

		File tempDir = (File)servletContext.getAttribute(
			JavaConstants.JAVAX_SERVLET_CONTEXT_TEMPDIR);

		sassTempDir = new File(tempDir, _SASS_DIR);

		sassTempDir.mkdirs();

		servletContext.setAttribute(_SASS_DIR_KEY, sassTempDir);

		return sassTempDir;
	}

	private static Theme _getTheme(HttpServletRequest request)
		throws Exception {

		long companyId = PortalUtil.getCompanyId(request);

		String themeId = ParamUtil.getString(request, "themeId");

		if (Validator.isNotNull(themeId)) {
			try {
				Theme theme = ThemeLocalServiceUtil.getTheme(
					companyId, themeId, false);

				return theme;
			}
			catch (Exception e) {
				_log.error(e, e);
			}
		}

		String requestURI = URLDecoder.decode(
			request.getRequestURI(), StringPool.UTF8);

		Matcher portalThemeMatcher = _portalThemePattern.matcher(requestURI);

		if (portalThemeMatcher.find()) {
			String themePathId = portalThemeMatcher.group(1);

			themePathId = StringUtil.replace(
				themePathId, StringPool.UNDERLINE, StringPool.BLANK);

			themeId = PortalUtil.getJsSafePortletId(themePathId);
		}
		else {
			Matcher pluginThemeMatcher = _pluginThemePattern.matcher(
				requestURI);

			if (pluginThemeMatcher.find()) {
				String themePathId = pluginThemeMatcher.group(1);

				themePathId = StringUtil.replace(
					themePathId, StringPool.UNDERLINE, StringPool.BLANK);

				StringBundler sb = new StringBundler(4);

				sb.append(themePathId);
				sb.append(PortletConstants.WAR_SEPARATOR);
				sb.append(themePathId);
				sb.append("theme");

				themePathId = sb.toString();

				themeId = PortalUtil.getJsSafePortletId(themePathId);
			}
		}

		if (Validator.isNull(themeId)) {
			return null;
		}

		try {
			Theme theme = ThemeLocalServiceUtil.getTheme(
				companyId, themeId, false);

			return theme;
		}
		catch (Exception e) {
			_log.error(e, e);
		}

		return null;
	}

	private static String _getThemeImagesPath(
			HttpServletRequest request, ThemeDisplay themeDisplay, Theme theme)
		throws Exception {

		String themeImagesPath = null;

		if (themeDisplay != null) {
			themeImagesPath = themeDisplay.getPathThemeImages();
		}
		else {
			String cdnHost = PortalUtil.getCDNHost(request);
			String themeStaticResourcePath = theme.getStaticResourcePath();

			themeImagesPath =
				cdnHost + themeStaticResourcePath + theme.getImagesPath();
		}

		return themeImagesPath;
	}

	private static boolean _isThemeCssFastLoad(
		HttpServletRequest request, ThemeDisplay themeDisplay) {

		if (themeDisplay != null) {
			return themeDisplay.isThemeCssFastLoad();
		}

		return SessionParamUtil.getBoolean(
			request, "css_fast_load", PropsValues.THEME_CSS_FAST_LOAD);
	}

	private static String _parseSass(
			ServletContext servletContext, HttpServletRequest request,
			ThemeDisplay themeDisplay, Theme theme, String resourcePath,
			String content)
		throws Exception {

		Map<String, Object> inputObjects = new HashMap<String, Object>();

		inputObjects.put("content", content);
		inputObjects.put("cssRealPath", resourcePath);
		inputObjects.put(
			"cssThemePath", _getCssThemePath(request, themeDisplay, theme));

		File sassTempDir = _getSassTempDir(servletContext);

		inputObjects.put("sassCachePath", sassTempDir.getCanonicalPath());

		UnsyncByteArrayOutputStream unsyncByteArrayOutputStream =
			new UnsyncByteArrayOutputStream();

		UnsyncPrintWriter unsyncPrintWriter = UnsyncPrintWriterPool.borrow(
			unsyncByteArrayOutputStream);

		inputObjects.put("out", unsyncPrintWriter);

		_rubyExecutor.eval(null, inputObjects, null, _rubyScript);

		unsyncPrintWriter.flush();

		return unsyncByteArrayOutputStream.toString();
	}

	/**
	 * @see {@link AggregateFilter#aggregateCss(String, String)}
	 */
	private static String propagateQueryString(
		String content, String queryString) {

		StringBuilder sb = new StringBuilder(content.length());

		int pos = 0;

		while (true) {
			int importX = content.indexOf(_CSS_IMPORT_BEGIN, pos);
			int importY = content.indexOf(
				_CSS_IMPORT_END, importX + _CSS_IMPORT_BEGIN.length());

			if ((importX == -1) || (importY == -1)) {
				sb.append(content.substring(pos));

				break;
			}

			sb.append(content.substring(pos, importY));
			sb.append(CharPool.QUESTION);
			sb.append(queryString);
			sb.append(_CSS_IMPORT_END);

			pos = importY + _CSS_IMPORT_END.length();
		}

		return sb.toString();
	}

	private static final String _CSS_IMPORT_BEGIN = "@import url(";

	private static final String _CSS_IMPORT_END = ");";

	private static final String _SASS_DIR = "sass";

	private static final String _SASS_DIR_KEY =
		DynamicCSSUtil.class.getName() + "#sass";

	private static Log _log = LogFactoryUtil.getLog(DynamicCSSUtil.class);

	private static Pattern _pluginThemePattern = Pattern.compile(
		"\\/([^\\/]+)-theme\\/", Pattern.CASE_INSENSITIVE);
	private static Pattern _portalThemePattern = Pattern.compile(
		"themes\\/([^\\/]+)\\/css", Pattern.CASE_INSENSITIVE);
	private static RubyExecutor _rubyExecutor = new RubyExecutor();
	private static String _rubyScript;

}