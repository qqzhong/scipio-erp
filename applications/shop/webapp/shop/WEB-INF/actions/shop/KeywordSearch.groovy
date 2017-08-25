/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/**
 * Keyword search script with solr support.
 * <p>
 * This script is also referenced by the shop's screens and
 * should not contain order component's specific code.
 * <p>
 * 2017-08-16: Script is revamped for fixes + field compatibility with old ofbiz KeywordSearch.groovy +
 * to avoid breaking compatibility with keywordsearch.ftl or other templates that might be using this.
 * TODO/FIXME: 2017-08-17: partial search support only; schema limitations; some parts of templates commented to avoid issues.
 */

import org.ofbiz.base.util.*;
import org.ofbiz.entity.condition.EntityCondition
import org.ofbiz.entity.condition.EntityOperator
import org.ofbiz.entity.util.EntityUtilProperties
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.product.catalog.*;
import org.ofbiz.product.feature.*;
import org.ofbiz.product.product.*;
import org.ofbiz.product.product.ProductSearchSession.ProductSearchOptions;
import org.ofbiz.product.product.ProductSearch.CategoryConstraint;
import org.ofbiz.product.product.ProductSearch.FeatureConstraint;
import org.ofbiz.product.product.ProductSearch.KeywordConstraint;
import org.ofbiz.product.product.ProductSearch.ProductSearchConstraint;
import org.ofbiz.product.product.ProductSearch.ProductSearchContext;
import org.ofbiz.product.product.ProductSearch.ResultSortOrder;
import org.ofbiz.product.product.ProductSearch.SortKeywordRelevancy;
import org.ofbiz.product.product.ProductSearch.SortProductField;
import org.ofbiz.product.product.ProductSearch.SortProductPrice;
import org.ofbiz.product.product.ProductSearch.SortProductFeature;
import org.ofbiz.product.category.CategoryWorker;
import com.ilscipio.solr.SolrUtil;

// SCIPIO: NOTE: This script is responsible for checking whether solr is applicable (if no check, implies the shop assumes solr is always enabled).
final String module = "KeywordSearch.groovy";
final boolean DEBUG = Debug.verboseOn();
//final boolean DEBUG = true;
final boolean useSolr = ("Y" == EntityUtilProperties.getPropertyValue("shop", "shop.useSolr", "Y", delegator)); // (TODO?: in theory this should be a ProductStore flag)

errorOccurred = false;
kwsArgs = [:];
ProductSearchOptions kwsParams = null;

handleException = { e ->
    // FIXME?: no clean way at the moment to identify when error is user input or system/code error;
    // so default message below tries to cover all.
    Debug.logError(e, "Error running solrKeywordSearch or processing parameters or results: " + e.getMessage()
        + "; search params: " + kwsArgs, module);
    def errorMessageList = context.errorMessageList;
    if (errorMessageList == null) errorMessageList = [];
    errorMessageList.add(context.kwsFailMsg ?: UtilProperties.getMessage("CommonErrorUiLabels", "CommonQueryErrorReviewContactSupport", context.locale));
    context.errorMessageList = errorMessageList;
    errorOccurred = true;
};

// SCIPIO: this allows to use the script for local scopes without affecting request
localVarsOnly = context.localVarsOnly;
if (localVarsOnly == null) localVarsOnly = false;
context.remove("localVarsOnly");

// SCIPIO: NOTE: there was inconsistency in ProductSearchSession, such that default noConditionFind
// is set to "N" in properties and in general, in the demo stores and in most logical stores,
// the ProductStore.prodSearchExcludeVariants=Y effectively cause noConditionFind to become Y.
// This might have been a bug, but everyone is probably used to it by now, so I've made a new
// property in shop.properties and set the default to Y (2017-08-17).
//defaultNoConditionFind = EntityUtilProperties.getPropertyValue("widget", "widget.defaultNoConditionFind", delegator);
defaultNoConditionFind = EntityUtilProperties.getPropertyValue("shop", "shop.search.defaultNoConditionFind", "Y", delegator);

noConditionFind = context.noConditionFind;
if (!localVarsOnly) {
    if (!noConditionFind) {
        noConditionFind = parameters.noConditionFind ? parameters.noConditionFind.toString() : null;
    }
}
if (!noConditionFind) noConditionFind = defaultNoConditionFind;

// we have to set this in parameters map and request attributes, EVEN IF localVarsOnly was passed!
// NOTE: we will restore it 

ncf = [reqRestore:false];

if (!parameters.noConditionFind) {
    // this is a convoluted piece to try to not affect the parameters and request attribs; discerns containsKey vs null
    ncf.reqRestore = true
    ncf.hadParamsKey = parameters.containsKey("noConditionFind");
    ncf.paramsVal = parameters.noConditionFind;
    ncf.hadReqAttr = false;
    Enumeration<String> reqAttrNames = request.getAttributeNames();
    while(reqAttrNames.hasMoreElements()) {
        if ("noConditionFind" == reqAttrNames.nextElement()) {
            ncf.hadReqAttr = true;
            break;
        }
    }
    ncf.reqAttr = request.getAttribute("noConditionFind");
    
    request.setAttribute("noConditionFind", noConditionFind);
    parameters.noConditionFind = noConditionFind;
}

restoreReqNoConditionFind = { 
    if (ncf.reqRestore) {
        if (ncf.hadParamsKey) parameters.noConditionFind = ncf.paramsVal;
        else parameters.remove("noConditionFind");
        if (ncf.hadReqAttr) request.setAttribute("noConditionFind", ncf.reqAttr);
        else request.removeAttribute("noConditionFind");
    }
};

try { // noConditionFind

if (context.useSolr == false || useSolr == false) {
    GroovyUtil.runScriptAtLocation("component://order/webapp/ordermgr/WEB-INF/actions/entry/catalog/KeywordSearch.groovy", null, context);
    return;
}

nowTimestamp = context.nowTimestamp ?: UtilDateTime.nowTimestamp();

sanitizeUserQueryExpr = { expr ->
    // TODO: this is extremely limited at the moment, only supports full solr syntax or exact string
    // FIXME: we can't use SolrUtil.escapeTermPlain due to issue with backslash-whitespace interpret
    if (expr instanceof String) {
        if (!expr) return expr;
        if (kwsArgs.searchSyntax == "full") return expr;
        else return SolrUtil.escapeTermFull(expr)
    } else if (expr instanceof List) {
        if (!expr) return expr;
        def resExprList = [];
        for(subExpr in expr) resExprList.add(sanitizeUserQueryExpr(subExpr));
        return resExprList;
    } else {
        return expr;
    }
};

// WARN: this one may add quotes (decision delegated)
escapeTerm = { term -> return SolrUtil.escapeTermFull(term); };

try {
    // allow full solr syntax in input search strings? (if false, searches exact string only)
    // PROBLEM: by allowing full query, user can easily cause crash - it's caught, but is unfriendly.
    // TODO?: should support an in-between pre-parsing, for user friendly and potential security reasons.
    kwsArgs.searchSyntax = context.searchSyntax != null ? context.searchSyntax : "full";
    kwsArgs.searchString = sanitizeUserQueryExpr(context.searchString); // WARN: setting this here overrides ALL the parameters.SEARCH_STRINGx expressions
    kwsArgs.searchFilters = context.searchFilters ?: []; // list
    kwsArgs.searchFilter = context.searchFilter; // string or list; if string, it's split on whitespace to make list
    kwsArgs.excludeVariants = context.searchExcludeVariants; // NOTE: should usually not specify this; is a ProductStore field
    kwsArgs.viewSize = context.viewSize;
    kwsArgs.viewIndex = context.viewIndex;
    kwsArgs.currIndex = context.currIndex; // TODO: REVIEW: why do we need a currIndex? isn't it same as viewIndex here?
    // SCIPIO: NOTE: in the original ProductSearchSession code, there was a disconnect between the paging
    // flag and the actual result paging; here we actually will honor the paging flag (if specified)
    kwsArgs.paging = context.paging; // Y/N indicator
    kwsArgs.searchCatalogs = context.searchCatalogs; // this is a LIMIT, for security reasons has to belong to current store
    kwsArgs.searchCategories = context.searchCategories;
    kwsArgs.searchFeatures = context.searchFeatures;
    kwsArgs.sortBy = context.searchSortBy;
    kwsArgs.sortByReverse = context.searchSortByReverse;
    kwsArgs.noConditionFind = noConditionFind;
    kwsArgs.searchSortOrderString = context.searchSortOrderString;
    kwsArgs.searchReturnFields = context.searchReturnFields;
    
    kwsParams = context.kwsParams; // user input parameters, only if localVarsOnly==false
    if (!localVarsOnly) {
        // REUSE the stock class where possibly so we might maintain some compatibility, duplicate less code,
        // and it does all the session stuff for us.
        // NOTE: at this time we might only support a subset of these parameters; the rest may be ignored; should be fine
        if (!kwsParams) {
            ProductSearchSession.processSearchParameters(parameters, request);
            kwsParams = ProductSearchSession.getProductSearchOptions(session);
        }
    
        if (!kwsArgs.currIndex) { // deprecated?
            kwsArgs.currIndex = parameters.CURR_INDEX ? parameters.CURR_INDEX.toString() : null;
        }
        
        // done below
        //if (!kwsArgs.searchString) {
        //    kwsArgs.searchString = parameters.SEARCH_STRING ? parameters.SEARCH_STRING.toString() : null;
        //}
        
        // TODO: REVIEW: can't allow this passed over params in this form... probably...
        //if (!kwsArgs.searchFilter) {
        //    kwsArgs.searchFilter = parameters.SEARCH_FILTER;
        //}
    }

    if (kwsParams) {
        if (!kwsArgs.viewSize) {
            kwsArgs.viewSize = kwsParams.getViewSize();
        }
        if (!kwsArgs.viewIndex) {
            kwsArgs.viewIndex = kwsParams.getViewIndex();
        }
        if (!kwsArgs.paging) {
            kwsArgs.paging = kwsParams.getPaging();
        }
    
        // CONVERT constraints to solr expression 
        // TODO: factor out somewhere
        // TODO: 2017-08-17: only the fields currently exposed in the shop UI are implemented; but several missing
        List<ProductSearchConstraint> pscList = kwsParams.getConstraintList();
        if (pscList) {
            // NOTE: basically, at the end, the OR list becomes a single entry of the AND list (this is ofbiz behavior,
            // and because the interface is ambiguous, better to preserve something known than introduce more randomness)
            kwExprList = [];
            
            kwCatalogCnsts = [];
            kwCategoryCnsts = [];
            kwFeatureCnsts = [];
            kwExcludeVariants = false;
            
            for(ProductSearchConstraint psc in pscList) {
                if (psc instanceof ProductSearch.ExcludeVariantsConstraint) {
                    kwExcludeVariants = true;
                } else if (psc instanceof ProductSearch.CatalogConstraint) { // SCIPIO: NOTE: we added this one here
                    ProductSearch.CatalogConstraint cc = (ProductSearch.CatalogConstraint) psc;
                    if (kwsArgs.searchCatalogs==null && cc.getProdCatalogId()) {
                        kwCatalogCnsts.add([
                            prodCatalogId: cc.getProdCatalogId()
                        ]);
                    }
                } else if (psc instanceof ProductSearch.CategoryConstraint) {
                    ProductSearch.CategoryConstraint cc = (ProductSearch.CategoryConstraint) psc;
                    if (kwsArgs.searchCategoryIds==null && cc.getProductCategoryId()) {
                        kwCategoryCnsts.add([
                            productCategoryId: cc.getProductCategoryId(), 
                            includeSub: cc.isIncludeSubCategories(), 
                            exclude: cc.getExclude()
                        ]);
                    }
                } else if (psc instanceof ProductSearch.FeatureConstraint) {
                    ProductSearch.FeatureConstraint fc = (ProductSearch.FeatureConstraint) psc;
                    if (kwsArgs.searchFeatures==null && fc.getProductFeatureId()) {
                        kwFeatureCnsts.add([
                            productFeatureId: fc.getProductFeatureId(),
                            exclude: fc.getExclude()
                        ]);
                    }
    //                featuresCount++;
    //                if (isNotFirst) {
    //                    searchParamString.append("&amp;");
    //                } else {
    //                    isNotFirst = true;
    //                }
    //                searchParamString.append("S_PFI");
    //                searchParamString.append(featuresCount);
    //                searchParamString.append("=");
    //                searchParamString.append(fc.productFeatureId);
    //                if (fc.exclude != null) {
    //                    searchParamString.append("&amp;S_PFX");
    //                    searchParamString.append(featuresCount);
    //                    searchParamString.append("=");
    //                    searchParamString.append(fc.exclude.booleanValue() ? "Y" : "N");
    //                }
                /* No way to specify parameters for these right now, so table until later
                } else if (psc instanceof ProductSearch.FeatureSetConstraint) {
                    ProductSearch.FeatureSetConstraint fsc = (ProductSearch.FeatureSetConstraint) psc;
                 */
                } else if (psc instanceof ProductSearch.FeatureCategoryConstraint) {
                    ProductSearch.FeatureCategoryConstraint pfcc = (ProductSearch.FeatureCategoryConstraint) psc;
                    // TODO?
    //                featureCategoriesCount++;
    //                if (isNotFirst) {
    //                    searchParamString.append("&amp;");
    //                } else {
    //                    isNotFirst = true;
    //                }
    //                searchParamString.append("S_FCI");
    //                searchParamString.append(featureCategoriesCount);
    //                searchParamString.append("=");
    //                searchParamString.append(pfcc.productFeatureCategoryId);
    //                if (pfcc.exclude != null) {
    //                    searchParamString.append("&amp;S_FCX");
    //                    searchParamString.append(featureCategoriesCount);
    //                    searchParamString.append("=");
    //                    searchParamString.append(pfcc.exclude.booleanValue() ? "Y" : "N");
    //                }
                } else if (psc instanceof ProductSearch.FeatureGroupConstraint) {
                    ProductSearch.FeatureGroupConstraint pfgc = (ProductSearch.FeatureGroupConstraint) psc;
                    // TODO?
    //                featureGroupsCount++;
    //                if (isNotFirst) {
    //                    searchParamString.append("&amp;");
    //                } else {
    //                    isNotFirst = true;
    //                }
    //                searchParamString.append("S_FGI");
    //                searchParamString.append(featureGroupsCount);
    //                searchParamString.append("=");
    //                searchParamString.append(pfgc.productFeatureGroupId);
    //                if (pfgc.exclude != null) {
    //                    searchParamString.append("&amp;S_FGX");
    //                    searchParamString.append(featureGroupsCount);
    //                    searchParamString.append("=");
    //                    searchParamString.append(pfgc.exclude.booleanValue() ? "Y" : "N");
    //                }
                } else if (psc instanceof ProductSearch.KeywordConstraint) {
                    ProductSearch.KeywordConstraint kc = (ProductSearch.KeywordConstraint) psc;
                    kwExpr = (kc.getKeywordsString() ?: "").trim();
                    if (kwExpr) {
                        // NOTE: OR is the usual default - we don't need to do anything in that case;
                        // but if AND is specified, then we need to add a "+" before every character...
                        if (kc.isAnd()) {
                            // WARN: FIXME: this is BEST-EFFORT - may break queries - see function
                            kwExprList.add(SolrUtil.addPrefixToAllKeywords(kwExpr, "+"));
                        } else {
                            kwExprList.add(kwExpr);
                        }
                        // TODO?: handle for cases where no full/solr syntax allowed:
                        //kc.isAnyPrefix()
                        //kc.isAnySuffix()
                    }
                } else if (psc instanceof ProductSearch.ListPriceRangeConstraint) {
                    ProductSearch.ListPriceRangeConstraint lprc = (ProductSearch.ListPriceRangeConstraint) psc;
                    // TODO?
    //                if (lprc.lowPrice != null || lprc.highPrice != null) {
    //                    if (isNotFirst) {
    //                        searchParamString.append("&amp;");
    //                    } else {
    //                        isNotFirst = true;
    //                    }
    //                    searchParamString.append("S_LPR");
    //                    searchParamString.append("=");
    //                    if (lprc.lowPrice != null) searchParamString.append(lprc.lowPrice);
    //                    searchParamString.append("_");
    //                    if (lprc.highPrice != null) searchParamString.append(lprc.highPrice);
    //                }
                } else if (psc instanceof ProductSearch.SupplierConstraint) {
                    ProductSearch.SupplierConstraint suppc = (ProductSearch.SupplierConstraint) psc;
                    // TODO?
    //                if (suppc.supplierPartyId != null) {
    //                    if (isNotFirst) {
    //                        searchParamString.append("&amp;");
    //                    } else {
    //                        isNotFirst = true;
    //                    }
    //                    searchParamString.append("S_SUP");
    //                    searchParamString.append("=");
    //                    searchParamString.append(suppc.supplierPartyId);
    //                }
                }
            }
            
            combineKwExpr = { exprList, joinOp ->
                if (exprList.size() == 1) return sanitizeUserQueryExpr(exprList[0]);
                StringBuilder sb = new StringBuilder();
                sb.append("(");
                sb.append(exprList[0]);
                sb.append(")");
                String joinOpFull = " " + joinOp + " (";
                for(int i=1; i<exprList.size(); i++) {
                    sb.append(joinOpFull);
                    sb.append(sanitizeUserQueryExpr(exprList[i]));
                    sb.append(")");
                }
                return sb.toString();
            };
            if (DEBUG) Debug.logInfo("Keyword search params: kwExprList: " + kwExprList, module);
            
            // make expression from AND list
            if (!kwsArgs.searchString && kwExprList) kwsArgs.searchString = combineKwExpr(kwExprList, "AND");
    
            if (kwsArgs.searchCatalogs==null && kwCatalogCnsts) kwsArgs.searchCatalogs = kwCatalogCnsts;
            if (kwsArgs.searchCategories==null && kwCategoryCnsts) kwsArgs.searchCategories = kwCategoryCnsts;
            if (kwsArgs.searchFeatures==null && kwFeatureCnsts) kwsArgs.searchFeatures = kwFeatureCnsts;
            if (kwsArgs.excludeVariants == null) kwsArgs.excludeVariants = kwExcludeVariants;
        }
        
        ResultSortOrder sortOrder = kwsParams.getResultSortOrder();
        if (!kwsArgs.sortBy && sortOrder != null) {
            if (sortOrder instanceof SortProductPrice) {
                SortProductPrice so = (SortProductPrice) sortOrder;
                kwsArgs.sortBy = com.ilscipio.solr.ProductUtil.getProductSolrPriceFieldNameFromEntityPriceType(so.getProductPriceTypeId(), 
                    context.locale, "Keyword search: ");
                if (kwsArgs.sortBy != "defaultPrice") {
                    // SPECIAL price search fallback - allows listPrice search to still work reasonably for products that don't have listPrice
                    // TODO?: REVIEW: query would be faster without function, but unclear if want to create
                    // a physical sortPrice or sortListPrice in the solr product schema
                    // the solr sortBy doesn't support sorting on the extra returnFields, apparently - at least not in this version
                    //kwsArgs.searchReturnFields = (kwsArgs.searchReturnFields ?: "*") + 
                    //    ",sortPrice=if(exists(" + kwsArgs.sortBy + ")," + kwsArgs.sortBy + ",defaultPrice)";
                    //kwsArgs.sortBy = "sortPrice";
                    kwsArgs.sortBy = "if(exists(" + kwsArgs.sortBy + ")," + kwsArgs.sortBy + ",defaultPrice)";
                }
                kwsArgs.sortByReverse = !so.isAscending();
                kwsArgs.searchSortOrderString = so.prettyPrintSortOrder(false, context.locale);
            } else if (sortOrder instanceof SortProductFeature) {
                // TODO?
                //SortProductFeature so = (SortProductFeature) sortOrder;
            } else if (sortOrder instanceof SortKeywordRelevancy) {
                SortKeywordRelevancy so = (SortKeywordRelevancy) sortOrder;
                kwsArgs.sortBy = null;
                kwsArgs.sortByReverse = null;
                //kwsArgs.sortByReverse = !so.isAscending();
                kwsArgs.searchSortOrderString = so.prettyPrintSortOrder(false, context.locale);
            } else if (sortOrder instanceof SortProductField) {
                SortProductField so = (SortProductField) sortOrder;
                simpleLocale = SolrUtil.getSolrSchemaLangLocale(context.locale) ?: Locale.ENGLISH;
                kwsArgs.sortBy = com.ilscipio.solr.ProductUtil.getProductSolrFieldNameFromEntity(so.getFieldName(), simpleLocale) ?: so.getFieldName();
                if (kwsArgs.sortBy) {
                    // FIXME: TEMPORARY WORKAROUND: sorting by any of the _i18n_ fields currently fails,
                    // so sort by internalName instead (non-localized)
                    if (kwsArgs.sortBy == "internalName" || kwsArgs.sortBy.contains("_i18n_")) {
                        kwsArgs.sortBy = "alphaNameSort";
                    }
                }
                kwsArgs.sortByReverse = !so.isAscending();
                kwsArgs.searchSortOrderString = so.prettyPrintSortOrder(false, context.locale);
            } else {
                Debug.logWarning("Solr: Keyword search: unrecognized sort order method: " + sortOrder.getClass().getName(), module);
            }
        }
    }
    if (!kwsArgs.searchCatalogs) kwsArgs.searchCatalogs = [CatalogWorker.getCurrentCatalogId(request)];
} catch(Exception e) {
    handleException(e);
}
    
   
if (!kwsArgs.searchSortOrderString) kwsArgs.searchSortOrderString = new org.ofbiz.product.product.ProductSearch.SortKeywordRelevancy().prettyPrintSortOrder(false, context.locale);

// NOTE: these context assigns are here in case of fail
// WARN: searchSortOrderString: should always be set to non-null, for the legacy template which crashes without it
context.searchSortOrderString = kwsArgs.searchSortOrderString;
context.paging = kwsArgs.paging; // in case fail
context.noConditionFind = kwsArgs.noConditionFind;
context.kwsParams = kwsParams;

if (!errorOccurred && ("Y".equals(kwsArgs.noConditionFind) || kwsArgs.searchString)) {
    try {
        if ("N".equals(kwsArgs.paging)) {
            // NOTE: null is for the service; these will be set to 0 upon return (for the template)
            kwsArgs.viewSize = null;
            kwsArgs.viewIndex = null;
            kwsArgs.currIndex = null;
        } else {
            if (kwsArgs.viewSize == null || kwsArgs.viewSize.toString().isEmpty()) kwsArgs.viewSize = UtilProperties.getPropertyAsInteger("general.properties", "record.paginate.defaultViewSize", 20);
            if (kwsArgs.viewIndex == null || kwsArgs.viewIndex.toString().isEmpty()) kwsArgs.viewIndex = 0;
            if (kwsArgs.currIndex == null || kwsArgs.currIndex.toString().isEmpty()) kwsArgs.currIndex = 1; // TODO: REVIEW: why 1??
        }
        
        if (kwsArgs.searchString) kwsArgs.searchString = kwsArgs.searchString.trim();
        else kwsArgs.searchString = "*:*";

        // FIXME?: whitespace escaping appears to not work...
        //else if (!kwsArgs.searchSyntax) kwsArgs.searchString = SolrUtil.escapeTermPlain(kwsArgs.searchString);
        
        // early assign for info when query throws error
        context.currentSearch = kwsArgs.searchString; // DEPRECATED?
        context.currentFilter = kwsArgs.searchFilter; // DEPRECATED?
        
        // This code was the opposite of the ofbiz query... what we need to return are
        // the virtual products (not its variants) PLUS the products that are neither virtual nor variant.
        // Using " -isVariant:true" should cover these two (~EntityCondition.makeCondition("prodIsVariant", EntityOperator.NOT_EQUAL, "Y")).
        //if (kwsArgs.searchFilter && !kwsArgs.searchFilter.contains("isVirtual")) kwsArgs.searchFilter += " -isVirtual:true";
        //else if (!kwsArgs.searchFilter) kwsArgs.searchFilter = " -isVirtual:true";
        
        if (kwsArgs.searchFilter) {
            if (kwsArgs.searchFilter instanceof String) {
                // WARN: for compat, here we emulate old solr query service and split on whitespace...
                kwsArgs.searchFilters.addAll(Arrays.asList(kwsArgs.searchFilter.trim().split("\\s+")));
            } else {
                kwsArgs.searchFilters.addAll(kwsArgs.searchFilter);
            }
        } 
        
        if (kwsArgs.excludeVariants) {
            hasVariantExpr = false;
            for(filter in kwsArgs.searchFilters) {
                // FIXME: heuristic regexp for isVariant: is unreliable and may be wrong... 
                // should defer to a parsing utility...
                if (filter.matches("^.*(\\+|-|\\b)isVariant:.*\$")) {
                    hasVariantExpr = true;
                    break;
                }
            }
            if (!hasVariantExpr) {
                // emulate ProductSearchSession EntityCondition.makeCondition("prodIsVariant", EntityOperator.NOT_EQUAL, "Y"),
                // which was roughly correct
                kwsArgs.searchFilters.add("-isVariant:true");
            }
        }
        
        searchCatalogIds = new HashSet<>();
        if (kwsArgs.searchCatalogs) {
            for(catalog in kwsArgs.searchCatalogs) {
                searchCatalogIds.add((catalog instanceof String) ? catalog : catalog.prodCatalogId);
            }
        }
        storeCatalogs = CatalogWorker.getStoreCatalogs(request);
        if (storeCatalogs) {
            catalogFilter = new StringBuilder();
            for (catalog in storeCatalogs) {
                if (!searchCatalogIds || searchCatalogIds.contains(catalog.prodCatalogId)) {
                    if (catalogFilter.length() > 0) catalogFilter.append(" OR ");
                    catalogFilter.append(escapeTerm(catalog.prodCatalogId));
                }
            }
            kwsArgs.searchFilters.add("+catalog:(" + catalogFilter.toString() + ")");
        }
        
        if (kwsArgs.searchCategories) {
            catExprList = [];
            for (category in kwsArgs.searchCategories) {
                if (category instanceof String) category = [productCategoryId:category];

                // NOTE: exclude is tri-state (follows CategoryConstraint, makes sense)
                StringBuilder sb = new StringBuilder();
                if (category.exclude != null) {
                    sb.append(category.exclude ? "-" : "+");
                }
                sb.append(SolrUtil.makeCategoryIdFieldQueryEscape("cat", category.productCategoryId, category.includeSub != false));
                catExprList.add(sb.toString());
            }
            // TODO: REVIEW: should this be a whole filter, or instead add each to searchFilters?
            kwsArgs.searchFilters.add(catExprList.join(" ")); 
        }
        
        /* TODO/FIXME: missing data in solr, can't implement...
        if (kwsArgs.searchFeatures) {
            catExprList = [];
            for (feature in kwsArgs.searchFeatures) {
                if (feature instanceof String) feature = [productFeatureId:feature];
                
                StringBuilder sb = new StringBuilder();
                if (category.exclude != null) {
                    sb.append(category.exclude ? "-" : "+");
                }
                sb.append("features:");
            }
        }*/
    
        // service requires these as string, but NOTE: it returns them as int
        if (kwsArgs.viewSize != null) kwsArgs.viewSize = kwsArgs.viewSize.toString();
        if (kwsArgs.viewIndex != null) kwsArgs.viewIndex = kwsArgs.viewIndex.toString();
        
        solrKwsServCtx = [query:kwsArgs.searchString, queryFilters:kwsArgs.searchFilters, 
            returnFields:kwsArgs.searchReturnFields,
            sortBy:kwsArgs.sortBy, sortByReverse:kwsArgs.sortByReverse,
            viewSize:kwsArgs.viewSize, viewIndex:kwsArgs.viewIndex, 
            locale:context.locale, userLogin:context.userLogin, timeZone:context.timeZone];
        
        if (DEBUG) Debug.logInfo("Keyword search params: " + kwsArgs, module);
        
        result = dispatcher.runSync("solrKeywordSearch", solrKwsServCtx, -1, true); // SEPARATE TRANSACTION so error doesn't crash screen
        
        if (DEBUG) Debug.logInfo("Keyword search results: " + result, module);
    
        if (ServiceUtil.isError(result)) {
            throw new Exception(ServiceUtil.getErrorMessage(result));
        }
        
        // SCIPIO: NOTE: the real paging mode depends on whether a kwsArgs.viewSize was in fact used in the query.
        // unlike old ofbiz stock code, we are binding the query pagination to the pagination display.
        kwsArgs.paging = (result.viewSize != null && result.viewSize > 0) ? "Y" : "N";
        if (kwsParams) kwsParams.setPaging(kwsArgs.paging); // update this after-the fact
        
        context.listIndex = 0;
        if (result.viewSize != null && result.viewSize > 0)
            context.listIndex = Math.ceil(result.listSize/result.viewSize);
        // SCIPIO: this may not make sense anymore since SOLR patches
        //if (!viewSize.equals(String.valueOf(result.viewSize))) {
        //    pageViewSize = Integer.parseInt(viewSize).intValue();
        //    context.listIndex = Math.ceil(result.listSize/pageViewSize);
        //    context.pageViewSize = pageViewSize;
        //}
        
        context.isCorrectlySpelled = result.isCorrectlySpelled;
        context.facetQueries = result.facetQueries;
        context.solrProducts = result.results; // new 2017-08-16: prefer this field name
        context.products = result.results; // LEGACY solr script field; for new code, solrProducts should be used, to disambiguate type
        context.listSize = result.listSize;
        context.viewIndex = result.viewIndex;
        context.viewSize = result.viewSize;
        
        context.suggestions = result.suggestions;
        context.searchConstraintStrings = [];
        context.currentSearch = kwsArgs.searchString; // DEPRECATED?
        context.currentFilter = kwsArgs.searchFilter; // DEPRECATED?
        
        // SCIPIO: 2017-08-16: the following context assignments are template compatibility/legacy fields - based on: 
        //   component://order/webapp/ordermgr/WEB-INF/actions/entry/catalog/KeywordSearch.groovy
        //<!-- LEGACY FIELDS
        productIds = [];
        if (context.products) {
            for(solrProduct in context.products) {
                productIds.add(solrProduct.productId);
            }
        }
        context.productIds = productIds;
        //context.viewIndex = result.viewIndex; // already set above
        //context.viewSize = result.viewSize; // already set above
        //context.listSize = result.listSize; // already set above
        // special non-null checks: just in case template depended on these being non-null (old code always had these non-null)
        if (context.viewIndex == null) context.viewIndex = 0;
        if (context.viewSize == null) context.viewSize = 0;
        if (context.listSize == null) context.listSize = 0;
        if (context.viewIndex != null && context.viewSize != null) {
            final viewIndexFirst = 0; // hopefully this will never change
            context.lowIndex = (context.viewIndex - viewIndexFirst) * context.viewSize;
            context.highIndex = ((context.viewIndex - viewIndexFirst) + 1) * context.viewSize;
        } else {
            context.lowIndex = 0;
            context.highIndex = 0;
        }
        context.paging = kwsArgs.paging;
        context.previousViewSize = context.viewSize; // FIXME?: we don't currently record any "previous" view size...
        //context.searchConstraintStrings = ...; // already set above
        //context.searchSortOrderString = ...; // already set above
        context.noConditionFind = kwsArgs.noConditionFind;
        //-->
        
        if (!kwsArgs.currIndex) context.currIndex = 1; // TODO: REVIEW: why 1??
        else if (!(kwsArgs.currIndex instanceof Integer)) context.currIndex = Integer.parseInt(kwsArgs.currIndex).intValue();    
        
        categoriesTrail = [:];
        for (facetField in result.facetFields.keySet())
            if (facetField.equals("cat"))
                categoriesTrail = result.facetFields.get(facetField);
        context.filterCategories = [:];
        for (categoryTrail in categoriesTrail.keySet()) {
            if (categoryTrail.split("/").length > 0) {
                productCategory = delegator.findOne("ProductCategory", UtilMisc.toMap("productCategoryId", categoryTrail.split("/")[categoryTrail.split("/").length - 1]), true)
                context.filterCategories.put(productCategory, UtilMisc.toMap(categoryTrail, categoriesTrail.get(categoryTrail)));
        //          Debug.log("filterCategory " + categoryTrail.split("/")[categoryTrail.split("/").length - 1]);
            }
        }
        
        /* SCIPIO: do NOT do this from here (not needed and may cause issues)
        parameters.VIEW_SIZE = result.viewSize;
        parameters.VIEW_INDEX = result.viewIndex;
        parameters.SEARCH_STRING = kwsArgs.searchString;
        parameters.SEARCH_FILTER = kwsArgs.searchFilter;
        parameters.CURR_INDEX = kwsArgs.currIndex;
        */
    } catch(Exception e) {
        handleException(e);
    }
}

} finally { // noConditionFind
    restoreReqNoConditionFind();
}

