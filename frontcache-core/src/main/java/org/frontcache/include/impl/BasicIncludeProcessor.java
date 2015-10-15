package org.frontcache.include.impl;

import javax.servlet.http.HttpServletRequest;

import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorBase;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class BasicIncludeProcessor extends IncludeProcessorBase implements IncludeProcessor {

	public BasicIncludeProcessor() {
	}

	/**
	 * 
	 * @param content
	 * @param appOriginBaseURL
	 * @return
	 */
	public String processIncludes(String content, String appOriginBaseURL, HttpServletRequest httpRequest)
	{
		StringBuffer outSb = new StringBuffer();
		
		int scanIdx = 0;
		while(scanIdx < content.length())
		{
			int startIdx = content.indexOf(START_MARKER, scanIdx);
			if (-1 < startIdx)
			{
				int endIdx = content.indexOf(END_MARKER, startIdx);
				if (-1 < endIdx)
				{
					String includeTagStr = content.substring(startIdx, endIdx + END_MARKER.length());
					String includeURL = getIncludeURL(includeTagStr);
					
					outSb.append(content.substring(scanIdx, startIdx));
					
					String includeContent = callInclude(appOriginBaseURL + includeURL, httpRequest);
					
					outSb.append(includeContent);
					
					scanIdx = endIdx + END_MARKER.length();
				} else {
					// can't find closing 
					outSb.append(content.substring(scanIdx, content.length()));
					scanIdx = content.length(); // scan complete
				}
				
				
			} else {
				outSb.append(content.substring(scanIdx, content.length()));
				scanIdx = content.length(); // scan complete
			}
		}

		return outSb.toString(); 
	}
	
}
