<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

  <#assign pcntListReadOnly = ((pcntListReadOnly!parameters.pcntListReadOnly!)?string) == "true"><#-- SCIPIO -->
  <#assign pcntPartyContentTypeId = pcntPartyContentTypeId!parameters.pcntPartyContentTypeId!?string>
  
  <@section id="partyContentList">
      <#if partyContent?has_content>
        <@table type="data-list"> <#-- orig: class="basic-table" --> <#-- orig: cellspacing="0" -->
          <@tbody>
          <#list partyContent as pContent>
            <#assign content = pContent.getRelatedOne("Content", false)>
            <#assign contentType = content.getRelatedOne("ContentType", true)>
            <#assign mimeType = content.getRelatedOne("MimeType", true)!>
            <#assign status = content.getRelatedOne("StatusItem", true)!>
            <#assign pcType = pContent.getRelatedOne("PartyContentType", false)>
            <@tr>
              <#-- SCIPIO: for inter-app linking 
                  TODO: REVIEW: it might be sane to assume the default for pcntListEditInter to be true instead of false... -->
              <#assign pcntListEditUri>EditPartyContents?contentId=${pContent.contentId}&amp;partyId=${pContent.partyId}&amp;partyContentTypeId=${pContent.partyContentTypeId}&amp;fromDate=${pContent.fromDate}</#assign>
              <#if ((pcntListEditInter!parameters.pcntListEditInter!)?string) == "true">
                <#assign pcntListEditLink><@ofbizInterWebappUrl extLoginKey=true>/partymgr/control/${pcntListEditUri}</@ofbizInterWebappUrl></#assign>
              <#else>
                <#assign pcntListEditLink><@ofbizUrl>${pcntListEditUri}</@ofbizUrl></#assign>
              </#if>
              <@td class="button-col"><a href="${pcntListEditLink}">${content.contentId}</a></@td>
              
            <#if !pcntPartyContentTypeId?has_content>
              <@td>${(pcType.get("description", locale))!}</@td>
            </#if>
            
              <@td>${content.contentName!}</@td>
              <#-- take too much space -->
              <#--<@td>${(contentType.get("description",locale))!}</@td>-->
              <#--<@td>${(mimeType.description)!}</@td>-->
              <@td>${(status.get("description",locale))!}</@td>
              <@td>${pContent.fromDate!}</@td>
              <@td class="button-col">
                <a href="<@ofbizUrl>img<#if (content.contentName?has_content)>/${content.contentName}</#if>?imgId=${(content.dataResourceId)!}</@ofbizUrl>" class="${styles.link_run_sys!} ${styles.action_view!}">${uiLabelMap.CommonView}</a>
              
              <#if !pcntListReadOnly>
                <#-- SCIPIO: WARN: for security reasons, we can currently only allow a view switch override here, not a full request URI 
                    - see also ContentList.ftl -->
                <#if !pcntListRemoveDonePage??>
                  <#assign pcntListRemoveDonePage = rawString(parameters.pcntListRemoveDonePage!)>
                  <#if pcntListRemoveDonePage?has_content>
                    <#assign pcntListRemoveDonePage = pcntListRemoveDonePage?replace("[^a-zA-Z0-9_-]+","")>
                  <#else>
                    <#assign pcntListRemoveDonePage = "viewprofile">
                  </#if>
                <#else>
                  <#assign pcntListRemoveDonePage = rawString(pcntListRemoveDonePage)>
                </#if>
                <#-- SCIPIO: TODO: WARN: this only removes the association, not the content itself! -->
                <form name="removePartyContent_${pContent_index}" method="post" action="<@ofbizUrl uri=("removePartyContent/"+pcntListRemoveDonePage) escapeAs='html'/>">
                  <input type="hidden" name="contentId" value="${pContent.contentId}" />
                  <input type="hidden" name="partyId" value="${pContent.partyId}" />
                  <input type="hidden" name="partyContentTypeId" value="${pContent.partyContentTypeId}" />
                  <input type="hidden" name="fromDate" value="${pContent.fromDate}" />
                  <a href="javascript:document.removePartyContent_${pContent_index}.submit()" class="${styles.link_run_sys!} ${styles.action_remove!}">${uiLabelMap.CommonRemove}</a>
                </form>
              </#if>
              
              </@td>
            </@tr>
          </#list>
          </@tbody>
        </@table>
      <#else>
        <@commonMsg type="result-norecord">${uiLabelMap.PartyNoContent}</@commonMsg>
      </#if>
  </@section>