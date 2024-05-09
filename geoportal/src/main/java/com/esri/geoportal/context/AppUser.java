/* See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * Esri Inc. licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.esri.geoportal.context;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.SecurityContext;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.web.client.RestTemplate;

import com.esri.geoportal.base.security.ArcGISAuthenticationProvider;
import com.esri.geoportal.base.security.Group;
import com.esri.geoportal.base.util.JsonUtil;

/**
 * The user associated with a request.
 */
public class AppUser {

  /* Instance variables. */
  private List<Group> groups;
  private boolean isAdmin;
  private boolean isAnonymous;
  private boolean isPublisher;
  private String username;
  
 
  /** Constructor */
  public AppUser() {}
  
  /**
   * Constructor.
   * @param request the request
   * @param sc the security context
   */
  public AppUser(HttpServletRequest request, SecurityContext sc) {
    init(request);
  }
  
  /**
   * Constructor.
   * @param username the username 
   * @param isAdmin True is this user has an ADMIN role
   * @param isPublisher True is this user has an PUBLISHER role
   */
  public AppUser(String username, boolean isAdmin, boolean isPublisher) {
    init(username,isAdmin,isPublisher);
  }
  
  /** The groups to which this use belongs. */
  public List<Group> getGroups() {
    return groups;
  }
  
  /** The username. */
  public String getUsername() {
    return username;
  }
  
  /** True if this user has an ADMIN role. */
  public boolean isAdmin() {
    return isAdmin;
  }
  
  /** True if this user is anonymous. */
  public boolean isAnonymous() {
    return isAnonymous;
  }
  
  /** True if this user has a PUBLISHER role */
  public boolean isPublisher() {
    return isPublisher;
  }
  
  /**
   * Initialize based upon an HTTP request.
   * @param request the request
   */
  private void init(HttpServletRequest request) {
    init(null,false,false);
    if (request == null) return;
    
    Principal p = request.getUserPrincipal();
    if (p == null) return;
    groups = new ArrayList<Group>();
    username = p.getName();
    if (username != null && username.length() > 0) {
      isAnonymous = false;
      isAdmin = request.isUserInRole("ADMIN");
      isPublisher = request.isUserInRole("PUBLISHER");
    } else {
      isAnonymous = true;
    }
    //System.err.println("username: "+username+", isAdmin="+isAdmin);
    
    String pfx = "ROLE_";
    String[] gtpRoles = {"ADMIN","PUBLISHER","USER"};
    List<String> gptRoleList = Arrays.asList(gtpRoles);
    Collection<GrantedAuthority> authorities = null;
    if (p instanceof UsernamePasswordAuthenticationToken) {
      UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken)p;
      if (auth.isAuthenticated()) authorities = auth.getAuthorities();
    } else if (p instanceof OAuth2Authentication) {
      OAuth2Authentication auth = (OAuth2Authentication)p;
      if (auth.isAuthenticated()) authorities = auth.getAuthorities();
    }
    String url ="";
    if (authorities != null) {
      Iterator<GrantedAuthority> iterator = authorities.iterator();
     
      if (iterator != null) {
        while (iterator.hasNext()){
          GrantedAuthority authority = iterator.next();
          if (authority != null) {
            String name = authority.getAuthority();
            if (name != null)
            {
            	// this role is ArcGIS token
            	if (name.indexOf("--urlWithToken--") != -1)
            	{            		
            		String urlPrefix = "--urlWithToken--";            		
            		url = name.substring(urlPrefix.length());
            	}
            	else
            	{
            		 if (name.indexOf(pfx) == 0) name = name.substring(pfx.length());
                     if (gptRoleList.indexOf(name.toUpperCase()) == -1) {
                       //System.err.println("authority: "+name);
                       groups.add(new Group(name));
                     }
            	}             
            }
          }
        }          
      }
    }
    
    //check username in GeoportalContext.getUserGroupMap. if it exists(in case of ArcGIS Authentication Provider), add those groups as well
    GeoportalContext gc = GeoportalContext.getInstance();
    HashMap<String,ArrayList<Group>> userGroupMap = gc.getUserGroupMap();
    if(userGroupMap.containsKey(username))
    {
    	groups.addAll(userGroupMap.get(username));
    }
    else
    {
    	//Try to retrieve groups from ArcGIS
    	if(url.length()>0)
    	{
    		this.getArcGISUserGroups(username,url);
    	}
    }    	
  }
  
  private void getArcGISUserGroups(String username, String url) {
	    ArcGISAuthenticationProvider provider = new ArcGISAuthenticationProvider();
	    RestTemplate rest = new RestTemplate();
	    HttpHeaders headers = new HttpHeaders();
	    String referer = provider.getThisReferer();
	    if (referer != null) {
	      headers.add("Referer",referer);
	    };
	    HttpEntity<String> requestEntity = new HttpEntity<String>(headers);
	    ResponseEntity<String> responseEntity = rest.exchange(url,HttpMethod.GET,requestEntity,String.class);
	    String response = responseEntity.getBody();
	    //System.err.println(response);;
	    //if (response != null) LOGGER.trace(response);
	    if (!responseEntity.getStatusCode().equals(HttpStatus.OK)) {
	      throw new AuthenticationServiceException("Error communicating with the authentication service.");
	    }
	    JsonObject jso = (JsonObject)JsonUtil.toJsonStructure(response);
	   
	    if (jso.containsKey("groups") && !jso.isNull("groups")) {
	        JsonArray jsoGroups = jso.getJsonArray("groups");
	        for (int i=0;i<jsoGroups.size();i++) {
	          JsonObject jsoGroup = jsoGroups.getJsonObject(i);
	          String groupId = jsoGroup.getString("id");
	          String groupName = jsoGroup.getString("title");
	          String groupKey = groupId+"_..._"+groupName;
	          groups.add(new Group(groupKey));
	        }
	    }
}

/**
   * Initialize.
   * @param username the username 
   * @param isAdmin True is this user has an ADMIN role
   * @param isPublisher True is this user has an PUBLISHER role
   */
  private void init(String username, boolean isAdmin, boolean isPublisher) {
    this.username = username;
    if (this.username != null && this.username.length() > 0) {
      this.isAnonymous = false;
      this.isAdmin = isAdmin;
      this.isPublisher = isPublisher;
    } else {
      isAnonymous = true;
      this.isAdmin = false;
      this.isPublisher = false;
    }
    
  }
  
}
