
package com.ilscipio.scipio.webtools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceDispatcher;


/**
 * Servlet used to serve entity export files, which basically consists in getting the
 * corresponding raw data from database and stream it in the response.
 */
@SuppressWarnings("serial")
public class ExportServlet extends HttpServlet {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    
    public ExportServlet() {
        super();
    }

    /**
     * @see javax.servlet.http.HttpServlet#init(javax.servlet.ServletConfig)
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
    }

    /**
     * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest,
     *      javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doGet(request, response);
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // SPECIAL: getDelegator/getDispatcher methods required so tenant db doesn't break (or breaks less)
        Delegator delegator = getDelegator(request);
        LocalDispatcher dispatcher = getDispatcher(request);
        Locale locale = UtilHttp.getLocale(request);
        GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
        
        String exportId = request.getParameter("exportId");
        
        
        GenericValue dataResource;
        try {
            if (UtilValidate.isNotEmpty(exportId)) {
                // this implies we're getting IMAGE_OBJECT type
                dataResource = EntityUtil.getFirst(EntityQuery.use(delegator).from("EntityExport").where("exportId", exportId).queryList());
                
                // see org.ofbiz.content.data.DataEvents#serveImage for reference code
                ServletContext application = request.getServletContext(); // SCIPIO: NOTE: no longer need getSession() for getServletContext(), since servlet API 3.0
                
                byte[] mediaData = (byte[]) dataResource.get("file");
                ByteArrayInputStream mediaStream = new ByteArrayInputStream(mediaData);

                long mediaLength = (long) dataResource.get("fileSize");
                
                response.setContentType("application/zip");
                response.setHeader("Content-Disposition", "inline; filename= " + exportId+".zip");
                response.setContentLengthLong(mediaLength);
                if (mediaData != null) {
                    response.getOutputStream().write(mediaData, 0, (int) mediaLength);
                } else if (mediaStream != null) {
                    UtilHttp.streamContent(response.getOutputStream(), mediaStream, (int) mediaLength);
                } else {
                    Debug.logError("Webtools: Bad stream/bytes source [effective exportId: " + exportId + "]", module);
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error"); // WARN: DO NOT send details, for security reasons
                    return;
                }
            }
                
                
           
                
            
            

        } catch (Exception e) {
            Debug.logError(e, module);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error"); // WARN: DO NOT send details, for security reasons
            return;
        }
    }

    /**
     * @see javax.servlet.http.HttpServlet#destroy()
     */
    @Override
    public void destroy() {
        super.destroy();
    }

    /**
     * SPECIAL: needed to not break tenant dbs.
     */
    private Delegator getDelegator(HttpServletRequest request) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        if (delegator != null) return delegator;
        delegator = (Delegator) request.getSession().getAttribute("delegator");
        if (delegator != null) return delegator;
        delegator = (Delegator) request.getServletContext().getAttribute("delegator");
        if (delegator != null) return delegator;
        return DelegatorFactory.getDelegator("default");
    }
    
    /**
     * SPECIAL: needed to not break tenant dbs.
     */
    private LocalDispatcher getDispatcher(HttpServletRequest request) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        if (dispatcher != null) return dispatcher;
        dispatcher = (LocalDispatcher) request.getSession().getAttribute("dispatcher");
        if (dispatcher != null) return dispatcher;
        dispatcher = (LocalDispatcher) request.getServletContext().getAttribute("dispatcher");
        if (dispatcher != null) return dispatcher;
        Delegator delegator = getDelegator(request);
        return ServiceDispatcher.getLocalDispatcher(delegator.getDelegatorName(), delegator);
    }
}
