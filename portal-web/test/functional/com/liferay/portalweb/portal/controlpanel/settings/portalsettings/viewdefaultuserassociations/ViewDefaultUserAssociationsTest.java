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

package com.liferay.portalweb.portal.controlpanel.settings.portalsettings.viewdefaultuserassociations;

import com.liferay.portalweb.portal.BaseTestCase;
import com.liferay.portalweb.portal.util.RuntimeVariables;

/**
 * @author Brian Wing Shun Chan
 */
public class ViewDefaultUserAssociationsTest extends BaseTestCase {
	public void testViewDefaultUserAssociations() throws Exception {
		selenium.selectWindow("null");
		selenium.selectFrame("relative=top");
		selenium.open("/web/guest/home/");
		selenium.clickAt("//div[@id='dockbar']",
			RuntimeVariables.replace("Dockbar"));
		selenium.waitForElementPresent(
			"//script[contains(@src,'/aui/aui-editable/aui-editable-min.js')]");
		assertEquals(RuntimeVariables.replace("Go to"),
			selenium.getText("//li[@id='_145_mySites']/a/span"));
		selenium.mouseOver("//li[@id='_145_mySites']/a/span");
		selenium.waitForVisible("link=Control Panel");
		selenium.clickAt("link=Control Panel",
			RuntimeVariables.replace("Control Panel"));
		selenium.waitForPageToLoad("30000");
		selenium.clickAt("link=Portal Settings",
			RuntimeVariables.replace("Portal Settings"));
		selenium.waitForPageToLoad("30000");
		assertTrue(selenium.isPartialText("//a[@id='_130_usersLink']", "Users"));
		selenium.clickAt("//a[@id='_130_usersLink']",
			RuntimeVariables.replace("Users"));
		selenium.clickAt("link=Default User Associations",
			RuntimeVariables.replace("Default User Associations"));
		selenium.waitForElementPresent(
			"//input[@name='_130_settings--admin.sync.default.associations--Checkbox']");
		assertTrue(selenium.isElementPresent(
				"//input[@name='_130_settings--admin.sync.default.associations--Checkbox']"));
		assertTrue(selenium.isElementPresent(
				"//textarea[@name='_130_settings--admin.default.group.names--']"));
		assertTrue(selenium.isElementPresent(
				"//textarea[@name='_130_settings--admin.default.role.names--']"));
		assertTrue(selenium.isElementPresent(
				"//textarea[@name='_130_settings--admin.default.user.group.names--']"));
	}
}