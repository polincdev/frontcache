<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://frontcache.org/core" prefix="fc" %>
<fc:component maxage="-1" />

			<div class="col-lg-4 r cacheable" style="height: 100px;">
			<a href='<c:url value="/" />'>Home Logo</a>			
			</div>

			<div class="col-lg-4 cacheable" style="height: 100px;">
			Search input
			</div>

			<div class="col-lg-4 l not-cacheable" style="height: 100px;">
	 			<fc:include url="/fcmvc/store/user-info" />
			</div>

			<div class="col-lg-12 t cacheable" style="height: 50px;">
			
				<%@ include file="/WEB-INF/jsp/store/fcmvc/main_menu.jsp" %>
			
			</div>

